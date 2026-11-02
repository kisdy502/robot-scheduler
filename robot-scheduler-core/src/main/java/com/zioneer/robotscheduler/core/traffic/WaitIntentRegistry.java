package com.zioneer.robotscheduler.core.traffic;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 交通等待意图登记表：tryAcquire 失败时记录机器人想要的资源（死锁检测输入）。
 */
@Component
public class WaitIntentRegistry {

    private final Map<String, WaitIntent> intentsByRobot = new ConcurrentHashMap<>();

    public void record(String robotCode, List<String> segmentIds) {
        intentsByRobot.put(robotCode, new WaitIntent(robotCode, List.copyOf(segmentIds),
                System.currentTimeMillis()));
    }

    public void clear(String robotCode) {
        intentsByRobot.remove(robotCode);
    }

    /** 清空全部等待意图（地图热切换）。 */
    public void clearAll() {
        intentsByRobot.clear();
    }

    public List<WaitIntent> snapshot() {
        // 超过 2 分钟的陈旧意图视为失效清除
        intentsByRobot.entrySet().removeIf(entry ->
                System.currentTimeMillis() - entry.getValue().recordedAtMillis() > 120_000);
        return List.copyOf(intentsByRobot.values());
    }

    public record WaitIntent(String robotCode, List<String> segmentIds, long recordedAtMillis) {
    }
}
