package com.zioneer.robotscheduler.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zioneer.robotscheduler.core.fleet.TaskAssignmentRegistry.OperationUse;
import com.zioneer.robotscheduler.core.repository.RobotRuntimeRepository;
import com.zioneer.robotscheduler.core.repository.RobotUsageSnapshot;
import com.zioneer.robotscheduler.core.traffic.TrafficSnapshot;
import com.zioneer.robotscheduler.persistence.entity.RobotRuntimeEntity;
import com.zioneer.robotscheduler.persistence.mapper.RobotRuntimeMapper;
import org.springframework.stereotype.Repository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** 单实例内串行更新同一运行正本；每次完整读改写，避免交通、使用权和序号彼此覆盖。 */
@Repository
@DependsOnDatabaseInitialization
public class MybatisRobotRuntimeRepository implements RobotRuntimeRepository {
    private static final int WRITE_TRANSACTION_TIMEOUT_SECONDS = 5;
    private final RobotRuntimeMapper mapper;
    private final TransactionTemplate transactions;

    /** 独立 MyBatis auto-commit 会话或 Mockito 测试入口。 */
    public MybatisRobotRuntimeRepository(RobotRuntimeMapper mapper) {
        this.mapper = mapper;
        this.transactions = null;
    }

    @Autowired
    public MybatisRobotRuntimeRepository(RobotRuntimeMapper mapper, PlatformTransactionManager manager) {
        this.mapper = mapper;
        this.transactions = new TransactionTemplate(manager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transactions.setTimeout(WRITE_TRANSACTION_TIMEOUT_SECONDS);
    }

    /** 提交完成后才退出单实例锁；调用者的外层事务回滚不能撤回已经确认的机器人占用。 */
    private <T> T commit(java.util.function.Supplier<T> action) {
        return transactions == null ? action.get() : transactions.execute(status -> action.get());
    }

    @Override
    public List<RobotUsageSnapshot> findUsages() {
        return mapper.selectList(new LambdaQueryWrapper<RobotRuntimeEntity>()).stream().map(row -> {
            OperationUse active = row.getOperationId() == null ? null : new OperationUse(row.getRobotCode(),
                    row.getTaskflowId(), row.getOperationId(), "running", millis(row.getOperationStartedAt()), null);
            OperationUse completed = row.getCompletedOperationId() == null ? null : new OperationUse(
                    row.getRobotCode(), row.getCompletedTaskflowId(), row.getCompletedOperationId(), "finished",
                    millis(row.getCompletedStartedAt()), millis(row.getCompletedFinishedAt()));
            return new RobotUsageSnapshot(row.getRobotCode(), row.getTaskflowId(), active, completed,
                    row.getMapName(), row.getMapVersion());
        }).toList();
    }

    @Override
    public synchronized void saveUsage(RobotUsageSnapshot state) {
        commit(() -> { saveUsageNow(state); return null; });
    }

    private void saveUsageNow(RobotUsageSnapshot state) {
        RobotRuntimeEntity row = existingOrNew(state.robotCode());
        boolean insert = row.getUpdatedAt() == null;
        row.setTaskflowId(state.taskflowId());
        OperationUse active = state.activeOperation();
        row.setOperationId(active == null ? null : active.operationId());
        row.setOperationStartedAt(active == null ? null : Instant.ofEpochMilli(active.startedAt()));
        OperationUse completed = state.completedOperation();
        row.setCompletedTaskflowId(completed == null ? null : completed.taskflowId());
        row.setCompletedOperationId(completed == null ? null : completed.operationId());
        row.setCompletedStartedAt(completed == null ? null : Instant.ofEpochMilli(completed.startedAt()));
        row.setCompletedFinishedAt(completed == null ? null : Instant.ofEpochMilli(completed.finishedAt()));
        if (state.mapName() != null) {
            row.setMapName(state.mapName());
            row.setMapVersion(state.mapVersion());
        }
        write(row, insert);
    }

    @Override
    public List<TrafficSnapshot> findTraffic() {
        return mapper.selectList(new LambdaQueryWrapper<RobotRuntimeEntity>()).stream().map(row ->
                new TrafficSnapshot(row.getRobotCode(), set(row.getSegmentIds()), set(row.getWaypointIds()),
                        row.getDwellWaypointId(), row.getDwellSegmentId(), Boolean.TRUE.equals(row.getAwaitingStop())
                                || row.getSchedulerTaskId() != null,
                        Boolean.TRUE.equals(row.getStopAccepted()), millis(row.getAcquiredAt()),
                        row.getMapName(), row.getMapVersion())).toList();
    }

    @Override
    public synchronized void saveTraffic(TrafficSnapshot state) {
        commit(() -> { saveTrafficNow(state); return null; });
    }

    private void saveTrafficNow(TrafficSnapshot state) {
        RobotRuntimeEntity row = existingOrNew(state.robotCode());
        boolean insert = row.getUpdatedAt() == null;
        row.setSegmentIds(new ArrayList<>(state.segmentIds()));
        row.setWaypointIds(new ArrayList<>(state.waypointIds()));
        row.setDwellWaypointId(state.dwellWaypointId());
        row.setDwellSegmentId(state.dwellSegmentId());
        if (Boolean.TRUE.equals(row.getAwaitingStop()) && !state.awaitingStop()) {
            row.setSchedulerTaskId(null);
        }
        row.setAwaitingStop(state.awaitingStop());
        row.setStopAccepted(state.stopAccepted());
        row.setAcquiredAt(state.acquiredAtMillis() == 0 ? null : Instant.ofEpochMilli(state.acquiredAtMillis()));
        row.setMapName(state.mapName());
        row.setMapVersion(state.mapVersion());
        write(row, insert);
    }

    @Override
    public synchronized int nextCommandId(String robotCode) {
        return commit(() -> allocateCommandId(robotCode));
    }

    private int allocateCommandId(String robotCode) {
        RobotRuntimeEntity row = existingOrNew(robotCode);
        boolean insert = row.getUpdatedAt() == null;
        int previous = row.getLastCommandId();
        // 首次升级没有旧序号正本，用正整数时间种子避开旧进程从 1 开始的常见序号。
        if (previous == 0) previous = (int) (Math.floorMod(Instant.now().getEpochSecond(), 1_000_000_000L)
                + 1_000_000_000L);
        int next = Math.incrementExact(previous);
        row.setLastCommandId(next);
        write(row, insert);
        return next;
    }

    @Override
    public synchronized void beginSchedulerExecution(String robot, String taskId, String mapName, String mapVersion) {
        commit(() -> {
            RobotRuntimeEntity row = existingOrNew(robot);
            boolean insert = row.getUpdatedAt() == null;
            row.setSchedulerTaskId(taskId);
            if (mapName != null) {
                row.setMapName(mapName);
                row.setMapVersion(mapVersion);
            }
            write(row, insert);
            return null;
        });
    }

    @Override
    public synchronized void finishSchedulerExecution(String robot, String taskId) {
        commit(() -> {
            RobotRuntimeEntity row = mapper.selectById(robot);
            // 旧执行线程退出时，新任务可能已进入执行，不能清掉新任务的标记。
            if (row != null && taskId.equals(row.getSchedulerTaskId())) {
                row.setSchedulerTaskId(null);
                write(row, false);
            }
            return null;
        });
    }

    private RobotRuntimeEntity existingOrNew(String robot) {
        RobotRuntimeEntity row = mapper.selectById(robot);
        if (row != null) return row;
        row = new RobotRuntimeEntity();
        row.setRobotCode(robot);
        row.setSegmentIds(List.of());
        row.setWaypointIds(List.of());
        row.setAwaitingStop(false);
        row.setStopAccepted(false);
        row.setLastCommandId(0);
        return row;
    }

    private void write(RobotRuntimeEntity row, boolean insert) {
        row.setUpdatedAt(Instant.now());
        int changed = insert ? mapper.insert(row) : mapper.updateById(row);
        if (changed != 1) throw new IllegalStateException("机器人运行正本写入失败: " + row.getRobotCode());
    }

    private static long millis(Instant value) { return value == null ? 0 : value.toEpochMilli(); }
    private static Set<String> set(List<String> value) { return value == null ? Set.of() : Set.copyOf(value); }
}
