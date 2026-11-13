package com.zioneer.robotscheduler.core.fleet;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 使用权租约看门狗：孤儿占用自动回收（30s 扫描）。
 *
 * <p>回收三条件（同时满足，防误杀活跃业务流）：
 * ① 心跳超时——owner 距最近一次活动（assign/goto/stop/显式心跳）超过 lease-timeout（默认 120s，0=禁用）；
 * ② owner 名下无未终态调度任务（排队/执行中）——任务本身就是活证明；
 * ③ （由①②联合保证）——不检查机器人本体状态：上层已死时本体在充电/静止均应回收。</p>
 *
 * <p>典型自愈形态：上层任务跑完后崩溃/忘记 release（压测事故形态）——2 分钟后自动归还，
 * 事件 USAGE/ORPHAN_RELEASED 审计可见；活跃业务流每 2 分钟内必有 API 活动或任务在跑，不受影响。</p>
 */
@Slf4j
@Component
public class OwnershipWatchdog {

    private final TaskAssignmentRegistry registry;
    private final long leaseTimeoutMillis;

    public OwnershipWatchdog(TaskAssignmentRegistry registry,
                             @Value("${robot.scheduler.ownership.lease-timeout-seconds:120}") long leaseTimeoutSeconds) {
        this.registry = registry;
        this.leaseTimeoutMillis = leaseTimeoutSeconds * 1000;
    }

    @Scheduled(fixedDelay = 30_000)
    public void scan() {
        if (leaseTimeoutMillis <= 0) {
            return;
        }
        Map<String, String> snapshot = registry.snapshot();
        if (snapshot.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Map.Entry<String, String> entry : snapshot.entrySet()) {
            String owner = entry.getKey();
            String robot = entry.getValue();
            Long heartbeat = registry.ownerHeartbeat(owner);
            if (heartbeat == null) {
                // 无心跳记录（旧数据/重启恢复缺失）：以扫描时刻补登记，下一轮再判
                registry.touch(owner);
                continue;
            }
            if (now - heartbeat <= leaseTimeoutMillis) {
                continue;
            }
            if (registry.hasActiveTasks(owner)) {
                continue;
            }
            String detail = "心跳超时 " + ((now - heartbeat) / 1000) + "s 且无活动任务";
            if (registry.orphanRelease(robot, owner, detail)) {
                log.info("使用权租约看门狗回收: robot={} owner={} {}", robot, owner, detail);
            }
        }
    }
}
