package com.zioneer.robotscheduler.core.traffic;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.jgrapht.graph.DefaultDirectedGraph;
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.alg.connectivity.KosarajuStrongConnectivityInspector;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** 基于当前点/段占用解析等待关系；识别循环等待组，不自动释放资源或发让路指令。 */
@Slf4j
@Component
public class DeadlockMonitor {
    private final WaitIntentRegistry waitIntents;
    private final TrafficRegistry trafficRegistry;

    public DeadlockMonitor(WaitIntentRegistry waitIntents, TrafficRegistry trafficRegistry) {
        this.waitIntents = waitIntents;
        this.trafficRegistry = trafficRegistry;
    }

    public Map<String, List<String>> waitingFor() {
        var occupied = trafficRegistry.occupationSnapshot();
        Map<String, List<String>> waiting = new TreeMap<>();
        for (var intent : waitIntents.snapshot()) {
            List<String> blockers = waitIntents.blockers(intent, occupied).stream()
                    .map(WaitIntentRegistry.Blocker::robotCode).toList();
            if (!blockers.isEmpty()) waiting.put(intent.robotCode(), blockers);
        }
        return java.util.Collections.unmodifiableMap(waiting);
    }

    @Scheduled(fixedDelay = 5000)
    public List<List<String>> detect() {
        var waiting = waitingFor();
        var graph = new DefaultDirectedGraph<String, DefaultEdge>(DefaultEdge.class);
        waiting.forEach((robot, blockers) -> {
            graph.addVertex(robot);
            blockers.forEach(graph::addVertex);
        });
        waiting.forEach((robot, blockers) -> blockers.forEach(blocker -> graph.addEdge(robot, blocker)));
        List<List<String>> groups = new KosarajuStrongConnectivityInspector<>(graph).stronglyConnectedSets()
                .stream().filter(group -> group.size() > 1)
                .map(group -> group.stream().sorted().toList())
                .sorted(java.util.Comparator.comparing(group -> group.get(0))).toList();
        for (var group : groups) {
            log.warn("检测到循环等待组: {}，等待关系={}；请检查离场路线和停靠点，超时不保证腾空道路",
                    group, waiting.entrySet().stream().filter(entry -> group.contains(entry.getKey())).toList());
        }
        return groups;
    }
}
