package com.zioneer.robotscheduler.core.traffic;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 死锁监控：构建"占用→等待"图检测环路（A 占的资源被 B 等待，B 占的资源被 A 等待）。
 *
 * <p>等待意图由 TrafficRegistry 的 tryAcquire 失败记录驱动（登记于等待表）；
 * 检测到环仅告警（P4）：任务的占用重试超时机制会自然打破环（失败释放资源）；
 * P5 引入让路点导航做无损打破。</p>
 */
@Slf4j
@Component
public class DeadlockMonitor {

    private final WaitIntentRegistry waitIntents;
    private final TrafficRegistry trafficRegistry;

    public DeadlockMonitor(WaitIntentRegistry waitIntents, TrafficRegistry trafficRegistry) {
        this.waitIntents = waitIntents;
        this.trafficRegistry = trafficRegistry;
    }

    @Scheduled(fixedDelay = 5000)
    public List<List<String>> detect() {
        Map<String, TrafficRegistry.OccupiedResources> occupation = trafficRegistry.occupationSnapshot();
        Map<String, List<String>> waitingFor = new HashMap<>();
        for (var intent : waitIntents.snapshot()) {
            List<String> blockers = new ArrayList<>();
            for (String segment : intent.segmentIds()) {
                String owner = findOwner(occupation, segment);
                if (owner != null && !owner.equals(intent.robotCode())) {
                    blockers.add(owner);
                }
            }
            if (!blockers.isEmpty()) {
                waitingFor.put(intent.robotCode(), blockers);
            }
        }
        List<List<String>> cycles = findCycles(waitingFor);
        for (List<String> cycle : cycles) {
            log.warn("检测到交通死锁环: {}（等待重试超时后将自动打破；P5 引入让路点无损打破）", cycle);
        }
        return cycles;
    }

    private String findOwner(Map<String, TrafficRegistry.OccupiedResources> occupation, String segment) {
        for (var entry : occupation.entrySet()) {
            if (entry.getValue().segmentIds().contains(segment)) {
                return entry.getKey();
            }
        }
        return null;
    }

    private List<List<String>> findCycles(Map<String, List<String>> waitingFor) {
        List<List<String>> cycles = new ArrayList<>();
        for (String start : waitingFor.keySet()) {
            List<String> path = new ArrayList<>();
            dfs(start, start, waitingFor, path, cycles);
        }
        return cycles;
    }

    private void dfs(String current, String target, Map<String, List<String>> waitingFor,
                     List<String> path, List<List<String>> cycles) {
        if (path.size() > 8 || path.contains(current)) {
            if (current.equals(target) && path.size() >= 2) {
                cycles.add(new ArrayList<>(path));
            }
            return;
        }
        path.add(current);
        for (String next : waitingFor.getOrDefault(current, List.of())) {
            dfs(next, target, waitingFor, path, cycles);
        }
        path.remove(path.size() - 1);
    }
}
