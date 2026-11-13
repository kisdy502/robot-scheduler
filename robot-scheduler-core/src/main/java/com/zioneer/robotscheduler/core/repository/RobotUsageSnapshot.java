package com.zioneer.robotscheduler.core.repository;

import com.zioneer.robotscheduler.core.fleet.TaskAssignmentRegistry.OperationUse;

/** 使用权与最近一次外部作业的持久化正本；不包含可由重启中断的调度任务线程。 */
public record RobotUsageSnapshot(String robotCode, String taskflowId, OperationUse activeOperation,
                                  OperationUse completedOperation, String mapName, String mapVersion,
                                  Long ownerSinceMillis, Long ownerHeartbeatMillis) {
}
