package com.zioneer.robotscheduler.core.fleet;

import com.zioneer.robotscheduler.core.dispatch.SchedulerHttpException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import com.zioneer.robotscheduler.core.map.MapManager;
import com.zioneer.robotscheduler.core.repository.RobotRuntimeRepository;
import com.zioneer.robotscheduler.core.repository.InMemoryRobotRuntimeRepository;
import com.zioneer.robotscheduler.core.repository.RobotUsageSnapshot;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** 使用权和外部作业先持久化再确认；运行中的调度任务由重启停止对账收口，不自动重放。 */
@Slf4j
@Component
public class TaskAssignmentRegistry {
    private final Map<String, String> assignments = new HashMap<>();
    private final Map<String, ScheduledUse> tasks = new HashMap<>();
    private final Map<String, OperationUse> operations = new HashMap<>();
    private volatile boolean externalBusy;
    private final Map<String, OperationUse> completedOperations = new HashMap<>();
    private final RobotRuntimeRepository repository;
    private final MapManager maps;
    private final RobotEventMonitor eventMonitor;

    public TaskAssignmentRegistry() { this(new InMemoryRobotRuntimeRepository(), null, null); }

    /** 便捷构造（无事件监测，测试/内嵌场景）。 */
    public TaskAssignmentRegistry(RobotRuntimeRepository repository, MapManager maps) {
        this(repository, maps, null);
    }

    @Autowired
    public TaskAssignmentRegistry(RobotRuntimeRepository repository, MapManager maps,
                                  @org.springframework.lang.Nullable RobotEventMonitor eventMonitor) {
        this.repository = repository;
        this.maps = maps;
        this.eventMonitor = eventMonitor;
        for (RobotUsageSnapshot saved : repository.findUsages()) {
            if (saved.taskflowId() != null) assignments.put(saved.taskflowId(), saved.robotCode());
            if (saved.activeOperation() != null) {
                operations.put(saved.robotCode(), saved.activeOperation());
                if (maps != null) maps.requireRecoveryMap(saved.mapName(), saved.mapVersion());
            }
            if (saved.completedOperation() != null) completedOperations.put(saved.robotCode(), saved.completedOperation());
        }
        externalBusy = !operations.isEmpty();
    }

    private void persist(String robot, String owner, OperationUse active, OperationUse completed) {
        var map = maps == null ? null : maps.activeSnapshot().orElse(null);
        try {
            repository.saveUsage(new RobotUsageSnapshot(robot, owner, active, completed,
                    map == null ? null : map.mapName(), map == null ? null : map.version()));
        } catch (RuntimeException failure) {
            log.error("使用权正本写入失败: robot_code={} taskflow_id={}", robot, owner, failure);
            throw new SchedulerHttpException(503, "robot usage could not be persisted; retry the same request");
        }
    }

    private record ScheduledUse(String owner, String robot, boolean executing) { }
    private final Map<String, String> finishingTasks = new HashMap<>();
    public record OperationUse(String robotCode, String taskflowId, String operationId,
                               String state, long startedAt, Long finishedAt) { }

    public synchronized void assign(String owner, String robot) {
        require(owner, "taskflow_id");
        require(robot, "robot_code");
        if (assignments.containsKey(owner) && !robot.equals(assignments.get(owner))) {
            throw SchedulerHttpException.conflict("taskflow already owns another robot");
        }
        assertCanUse(robot, owner);
        if (tasks.values().stream().anyMatch(t -> owner.equals(t.owner()) && t.robot() != null
                && !robot.equals(t.robot()))) {
            throw SchedulerHttpException.conflict("taskflow has unfinished tasks on another robot");
        }
        if (tasks.values().stream().anyMatch(t -> robot.equals(t.robot()) && !Objects.equals(owner, t.owner()))) {
            throw SchedulerHttpException.conflict("robot has unfinished scheduler tasks");
        }
        persist(robot, owner, operations.get(robot), completedOperations.get(robot));
        assignments.put(owner, robot);
        if (eventMonitor != null) eventMonitor.onUsage(robot, owner, true);
        log.info("机器人使用权登记: taskflow_id={} robot_code={}", owner, robot);
    }

    public synchronized boolean release(String owner, String robot) {
        require(owner, "taskflow_id");
        String assigned = assignments.get(owner);
        if (assigned == null) {
            if (robot != null && ownerOf(robot) != null) {
                throw SchedulerHttpException.conflict("robot belongs to another taskflow");
            }
            return false;
        }
        if (robot != null && !robot.isBlank() && !robot.equals(assigned)) {
            throw SchedulerHttpException.conflict("robot does not match taskflow ownership");
        }
        if (operations.containsKey(assigned) || tasks.values().stream().anyMatch(t ->
                assigned.equals(t.robot()) || owner.equals(t.owner()))) {
            throw SchedulerHttpException.conflict("robot has unfinished work; finish or cancel it before release");
        }
        persist(assigned, null, null, completedOperations.get(assigned));
        assignments.remove(owner);
        if (eventMonitor != null) eventMonitor.onUsage(assigned, owner, false);
        log.info("机器人使用权释放: taskflow_id={} robot_code={}", owner, assigned);
        return true;
    }

    public synchronized boolean canUse(String robot, String owner) {
        String claimed = ownerOf(robot);
        String ownedRobot = owner == null ? null : assignments.get(owner);
        return (claimed == null || claimed.equals(owner)) && (ownedRobot == null || ownedRobot.equals(robot));
    }

    public synchronized void assertCanUse(String robot, String owner) {
        if (!canUse(robot, owner)) throw SchedulerHttpException.conflict("robot ownership conflicts with taskflow_id");
    }

    public synchronized boolean canAllocate(String robot, String owner) {
        return canUse(robot, owner) && !operations.containsKey(robot)
                && tasks.values().stream().noneMatch(t -> robot.equals(t.robot()));
    }

    public synchronized boolean canExecute(String robot, String owner) {
        return canUse(robot, owner) && !operations.containsKey(robot);
    }

    public synchronized void registerTask(String id, String robot, String owner) {
        if (robot != null) assertCanUse(robot, owner);
        tasks.put(id, new ScheduledUse(owner, robot, false));
    }

    public synchronized boolean assignTask(String id, String robot) {
        ScheduledUse task = tasks.get(id);
        if (task == null || !canAllocate(robot, task.owner())) return false;
        tasks.put(id, new ScheduledUse(task.owner(), robot, false));
        return true;
    }

    public synchronized String taskOwner(String id) {
        ScheduledUse task = tasks.get(id);
        return task == null ? null : task.owner();
    }

    /** 只有基础就绪且没有外部作业时才登记实际执行，不能把外部作业期间的排队任务当成残留命令。 */
    public synchronized boolean beginExecution(String id) {
        ScheduledUse task = tasks.get(id);
        if (task == null || task.robot() == null || !canExecute(task.robot(), task.owner())) return false;
        if (!task.executing()) {
            var map = maps == null ? null : maps.activeSnapshot().orElse(null);
            repository.beginSchedulerExecution(task.robot(), id, map == null ? null : map.mapName(),
                    map == null ? null : map.version());
            tasks.put(id, new ScheduledUse(task.owner(), task.robot(), true));
        }
        return true;
    }

    public synchronized void finishTask(String id) {
        ScheduledUse task = tasks.get(id);
        if (task == null) return;
        if (task.executing()) {
            finishingTasks.put(id, task.robot());
            repository.finishSchedulerExecution(task.robot(), id);
        }
        finishingTasks.remove(id);
        tasks.remove(id);
    }

    /** 写盘恢复后收口已退出的执行登记；失败期间继续阻止释放或开始外部作业。 */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 5000)
    public synchronized void retryFinishingTasks() {
        for (var entry : Map.copyOf(finishingTasks).entrySet()) {
            try {
                repository.finishSchedulerExecution(entry.getValue(), entry.getKey());
                tasks.remove(entry.getKey());
                finishingTasks.remove(entry.getKey());
            } catch (RuntimeException failure) {
                log.warn("执行登记待持久化收口: robot_code={} task_id={}", entry.getValue(), entry.getKey(), failure);
                // 存储故障时本轮止步，避免三十台逐一等待连接而长时间占住使用权锁。
                break;
            }
        }
    }

    public synchronized void assertCanStopOrphan(String robot, String owner) {
        assertCanUse(robot, owner);
        if (operations.containsKey(robot) || tasks.values().stream().anyMatch(t -> robot.equals(t.robot()))) {
            throw SchedulerHttpException.conflict("robot is executing other work; orphan cancellation cannot stop it");
        }
    }

    public synchronized OperationUse startOperation(String owner, String robot, String operation) {
        validateOperation(owner, robot, operation);
        OperationUse active = operations.get(robot);
        if (active != null) {
            if (matches(active, owner, operation)) return active;
            throw SchedulerHttpException.conflict("robot already has an external operation");
        }
        OperationUse completed = completedOperations.get(robot);
        if (completed != null && matches(completed, owner, operation)) return completed;
        if (tasks.values().stream().anyMatch(t -> robot.equals(t.robot()) || owner.equals(t.owner()))) {
            throw SchedulerHttpException.conflict("scheduler tasks must finish before external operation starts");
        }
        OperationUse started = new OperationUse(robot, owner, operation, "running", System.currentTimeMillis(), null);
        persist(robot, owner, started, completedOperations.get(robot));
        operations.put(robot, started);
        externalBusy = true;
        log.info("外部作业开始: taskflow_id={} robot_code={} operation_id={}", owner, robot, operation);
        return started;
    }

    public synchronized OperationUse finishOperation(String owner, String robot, String operation) {
        validateOperation(owner, robot, operation);
        OperationUse completed = completedOperations.get(robot);
        if (completed != null && matches(completed, owner, operation)) return completed;
        OperationUse active = operations.get(robot);
        if (active == null || !matches(active, owner, operation)) {
            throw SchedulerHttpException.conflict("external operation does not match active operation");
        }
        OperationUse finished = new OperationUse(robot, owner, operation, "finished", active.startedAt(),
                System.currentTimeMillis());
        persist(robot, owner, null, finished);
        operations.remove(robot);
        externalBusy = !operations.isEmpty();
        completedOperations.put(robot, finished);
        log.info("外部作业结束: taskflow_id={} robot_code={} operation_id={}", owner, robot, operation);
        return finished;
    }

    private void validateOperation(String owner, String robot, String operation) {
        require(owner, "taskflow_id"); require(robot, "robot_code"); require(operation, "operation_id");
        if (!owner.equals(ownerOf(robot))) throw SchedulerHttpException.conflict("claim robot before external operation");
    }
    private static boolean matches(OperationUse use, String owner, String operation) {
        return use.taskflowId().equals(owner) && use.operationId().equals(operation);
    }
    private static void require(String value, String field) {
        if (value == null || value.isBlank()) throw SchedulerHttpException.badRequest(field + " is required");
    }
    public synchronized String ownerOf(String robot) {
        return assignments.entrySet().stream().filter(e -> e.getValue().equals(robot))
                .map(Map.Entry::getKey).findFirst().orElse(null);
    }
    public synchronized OperationUse activeOperation(String robot) { return operations.get(robot); }
    public boolean hasExternalOperations() { return externalBusy; }
    public synchronized Map<String, String> snapshot() { return Map.copyOf(assignments); }
    public synchronized boolean isAssigned(String owner) { return assignments.containsKey(owner); }
}
