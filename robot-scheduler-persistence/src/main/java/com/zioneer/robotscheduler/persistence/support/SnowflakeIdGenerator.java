package com.zioneer.robotscheduler.persistence.support;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;

/**
 * 雪花 ID 生成器（对齐 robot-common-mybatis 同名组件；独立自持避免跨仓库 SNAPSHOT 耦合）。
 * epoch=2025-01-01，hostname/runtime 派生 workerId 保证多实例尽量离散。
 */
public class SnowflakeIdGenerator {

    private static final long EPOCH = 1735689600000L;
    private static final long WORKER_ID_BITS = 5L;
    private static final long DATACENTER_ID_BITS = 5L;
    private static final long SEQUENCE_BITS = 12L;
    private static final long MAX_WORKER_ID = ~(-1L << WORKER_ID_BITS);
    private static final long MAX_DATACENTER_ID = ~(-1L << DATACENTER_ID_BITS);
    private static final long WORKER_ID_SHIFT = SEQUENCE_BITS;
    private static final long DATACENTER_ID_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS;
    private static final long TIMESTAMP_LEFT_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS + DATACENTER_ID_BITS;
    private static final long SEQUENCE_MASK = ~(-1L << SEQUENCE_BITS);

    private final long workerId;
    private final long datacenterId;
    private long sequence;
    private long lastTimestamp = -1L;

    public SnowflakeIdGenerator() {
        this.datacenterId = buildDatacenterId();
        this.workerId = buildWorkerId();
    }

    /**
     * 同步生成下一个唯一 ID（时钟回拨时自旋等待）。
     */
    public synchronized long nextId() {
        long timestamp = currentTime();
        if (timestamp < lastTimestamp) {
            timestamp = waitUntil(lastTimestamp);
        }
        if (timestamp == lastTimestamp) {
            sequence = (sequence + 1) & SEQUENCE_MASK;
            if (sequence == 0) {
                timestamp = waitUntil(lastTimestamp + 1);
            }
        } else {
            sequence = 0L;
        }
        lastTimestamp = timestamp;
        return ((timestamp - EPOCH) << TIMESTAMP_LEFT_SHIFT)
                | (datacenterId << DATACENTER_ID_SHIFT)
                | (workerId << WORKER_ID_SHIFT)
                | sequence;
    }

    private long waitUntil(long targetTimestamp) {
        long timestamp = currentTime();
        while (timestamp < targetTimestamp) {
            timestamp = currentTime();
        }
        return timestamp;
    }

    private long currentTime() {
        return System.currentTimeMillis();
    }

    private long buildDatacenterId() {
        try {
            String host = InetAddress.getLocalHost().getHostName();
            return Math.floorMod(host.hashCode(), (int) (MAX_DATACENTER_ID + 1));
        } catch (Exception ex) {
            return 0L;
        }
    }

    private long buildWorkerId() {
        try {
            String runtime = ManagementFactory.getRuntimeMXBean().getName();
            return Math.floorMod(runtime.hashCode(), (int) (MAX_WORKER_ID + 1));
        } catch (Exception ex) {
            return 0L;
        }
    }
}
