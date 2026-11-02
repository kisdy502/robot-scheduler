package com.zioneer.robotscheduler.core.fleet;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 任务流机器人占用登记（对齐旧栈 bridge._assignments：assign-task/release-robot 的内存记录，
 * 仅用于上层释放语义，不参与调度决策）。
 */
@Component
public class TaskAssignmentRegistry {

    /** taskflowId → robotCode。 */
    private final Map<String, String> assignments = new ConcurrentHashMap<>();

    public void assign(String taskflowId, String robotCode) {
        assignments.put(taskflowId, robotCode);
    }

    /** 释放整个 taskflow 或指定机器人；返回是否有变化。 */
    public boolean release(String taskflowId, String robotCode) {
        if (robotCode != null && !robotCode.isBlank()) {
            return robotCode.equals(assignments.remove(taskflowId));
        }
        return assignments.remove(taskflowId) != null;
    }

    public Map<String, String> snapshot() {
        return Map.copyOf(assignments);
    }

    public boolean isAssigned(String taskflowId) {
        return assignments.containsKey(taskflowId);
    }
}
