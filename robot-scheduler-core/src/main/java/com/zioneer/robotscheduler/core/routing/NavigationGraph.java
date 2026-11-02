package com.zioneer.robotscheduler.core.routing;

import com.zioneer.robotscheduler.core.map.FloorMetadata;
import com.zioneer.robotscheduler.core.map.MapMetadata;
import com.zioneer.robotscheduler.core.map.PathMetadata;
import com.zioneer.robotscheduler.core.map.PathSegmentMetadata;
import com.zioneer.robotscheduler.core.map.WaypointMetadata;
import org.jgrapht.GraphPath;
import org.jgrapht.alg.shortestpath.DijkstraShortestPath;
import org.jgrapht.graph.SimpleDirectedWeightedGraph;
import org.jgrapht.graph.AsWeightedGraph;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 导航图：由 {@link MapMetadata} 构建的楼层规划图。
 *
 * <p>构建规则：
 * 节点 = waypoint id；边 = path.waypoints 相邻段展开；
 * 段限速 = min(path.maxSpeed, 楼层默认速度, 与段相交区域约束的 maxSpeed)；
 * forbidden 区域（maxSpeed=0）相交的段不建边；
 * 双向路径补反向边。跨层连接本期仅建立索引（跨层任务调度 P4+）。</p>
 */
public class NavigationGraph {

    private final MapMetadata source;

    /** floorId → 楼层规划图。 */
    private final Map<String, FloorGraph> floorGraphs = new LinkedHashMap<>();

    /** floorId → waypointId → 点位。 */
    private final Map<String, Map<String, WaypointMetadata>> waypointsByFloor = new LinkedHashMap<>();

    private NavigationGraph(MapMetadata source) {
        this.source = source;
    }

    /** 从地图元数据构建规划图（不可变对象，地图热切换时整体替换）。 */
    public static NavigationGraph from(MapMetadata metadata) {
        NavigationGraph graph = new NavigationGraph(metadata);
        for (FloorMetadata floor : metadata.getFloors()) {
            Map<String, WaypointMetadata> waypointIndex = new LinkedHashMap<>();
            floor.getWaypoints().forEach(waypoint -> waypointIndex.put(waypoint.getId(), waypoint));
            graph.waypointsByFloor.put(floor.getFloorId(), waypointIndex);
            graph.floorGraphs.put(floor.getFloorId(), buildFloorGraph(floor, waypointIndex));
        }
        return graph;
    }

    private static FloorGraph buildFloorGraph(FloorMetadata floor, Map<String, WaypointMetadata> waypointIndex) {
        SimpleDirectedWeightedGraph<String, SegmentEdge> jgraph =
                new SimpleDirectedWeightedGraph<>(SegmentEdge.class);
        waypointIndex.keySet().forEach(jgraph::addVertex);
        for (PathMetadata path : floor.getPaths()) {
            for (int i = 0; i + 1 < path.getWaypoints().size(); i++) {
                String fromId = path.getWaypoints().get(i);
                String toId = path.getWaypoints().get(i + 1);
                WaypointMetadata from = waypointIndex.get(fromId);
                WaypointMetadata to = waypointIndex.get(toId);
                Double segmentSpeed = resolveSegmentSpeed(floor, path, from, to);
                if (segmentSpeed == null || segmentSpeed <= 0) {
                    // forbidden 区域相交段：禁行不建边
                    continue;
                }
                double length = resolveSegmentLength(path, from, to, fromId, toId);
                addEdge(jgraph, segmentId(path.getId(), fromId, toId), path, fromId, toId, length, segmentSpeed);
                if (path.isBidirectional()) {
                    double reverseLength = resolveSegmentLength(path, from, to, toId, fromId);
                    addEdge(jgraph, segmentId(path.getId(), toId, fromId), path, toId, fromId,
                            reverseLength, segmentSpeed);
                }
            }
        }
        return new FloorGraph(jgraph, floor);
    }

    /**
     * 段长度：直线段取欧氏距离；CURVE 段按三次贝塞尔采样折线积分（曲线更长，影响 ETA 与占用时长）。
     */
    private static double resolveSegmentLength(PathMetadata path, WaypointMetadata from, WaypointMetadata to,
                                               String fromId, String toId) {
        PathSegmentMetadata segment = path.segmentBetween(fromId, toId);
        if (segment != null && segment.isCurve()) {
            return bezierCurveLength(from.getX(), from.getY(),
                    segment.getControlPoints()[0][0], segment.getControlPoints()[0][1],
                    segment.getControlPoints()[1][0], segment.getControlPoints()[1][1],
                    to.getX(), to.getY());
        }
        return Math.hypot(to.getX() - from.getX(), to.getY() - from.getY());
    }

    /** 三次贝塞尔曲线长度（16 段折线采样积分，精度满足 ETA 估算）。 */
    static double bezierCurveLength(double x0, double y0, double x1, double y1,
                                    double x2, double y2, double x3, double y3) {
        int samples = 16;
        double length = 0;
        double prevX = x0;
        double prevY = y0;
        for (int i = 1; i <= samples; i++) {
            double t = (double) i / samples;
            double u = 1 - t;
            double x = u * u * u * x0 + 3 * u * u * t * x1 + 3 * u * t * t * x2 + t * t * t * x3;
            double y = u * u * u * y0 + 3 * u * u * t * y1 + 3 * u * t * t * y2 + t * t * t * y3;
            length += Math.hypot(x - prevX, y - prevY);
            prevX = x;
            prevY = y;
        }
        return length;
    }

    private static void addEdge(SimpleDirectedWeightedGraph<String, SegmentEdge> jgraph, String segmentId,
                                PathMetadata path, String fromId, String toId,
                                double length, double segmentSpeed) {
        // 平行边防御：同顶点对已有边时跳过（重复路径段属地图配置问题）
        if (jgraph.containsEdge(fromId, toId)) {
            return;
        }
        SegmentEdge edge = new SegmentEdge(segmentId, path.getId(), fromId, toId, length,
                segmentSpeed, path.isEnableObstacleAvoidance());
        jgraph.addEdge(fromId, toId, edge);
        jgraph.setEdgeWeight(edge, length / segmentSpeed);
    }

    /**
     * 段限速合并：min(path.maxSpeed ?? floor.defaultMaxSpeed, 相交区域 maxSpeed 集合)；
     * 区域重叠优先级不影响"取最小值"结果（地图格式文档 §十：多重叠加取最严格限制）。
     *
     * @return null 或 0 表示禁行
     */
    private static Double resolveSegmentSpeed(FloorMetadata floor, PathMetadata path,
                                              WaypointMetadata from, WaypointMetadata to) {
        double speed = path.getMaxSpeed() == null ? floor.getDefaultMaxSpeed() : path.getMaxSpeed();
        for (var zone : floor.getZoneConstraints()) {
            if (zone.getVertices() == null || zone.getMaxSpeed() == null) {
                continue;
            }
            if (GeometryUtil.segmentIntersectsPolygon(from.getX(), from.getY(), to.getX(), to.getY(),
                    zone.getVertices())) {
                if (zone.getMaxSpeed() <= 0) {
                    return 0.0;
                }
                speed = Math.min(speed, zone.getMaxSpeed());
            }
        }
        return speed;
    }

    private static String segmentId(String pathId, String fromId, String toId) {
        // 双向路径统一段 ID（无序点对）：对向通行互斥，防单线路面对面物理对撞
        String first = fromId.compareTo(toId) <= 0 ? fromId : toId;
        String second = fromId.compareTo(toId) <= 0 ? toId : fromId;
        return pathId + "#" + first + "-" + second;
    }

    // ==================== 查询 API ====================

    /** 源元数据（只读引用，热切换判断用）。 */
    public MapMetadata source() {
        return source;
    }

    public boolean isLoaded() {
        return !floorGraphs.isEmpty();
    }

    public List<String> floorIds() {
        return List.copyOf(floorGraphs.keySet());
    }

    /** 按楼层+索引取点位。 */
    public Optional<WaypointMetadata> waypoint(String floorId, String waypointId) {
        return Optional.ofNullable(waypointsByFloor.getOrDefault(floorId, Map.of()).get(waypointId));
    }

    /** 全图按 id 解析点位（id 全局唯一时直接命中；跨层时优先默认楼层）。 */
    public Optional<WaypointMetadata> waypoint(String waypointId) {
        for (Map<String, WaypointMetadata> index : waypointsByFloor.values()) {
            WaypointMetadata waypoint = index.get(waypointId);
            if (waypoint != null) {
                return Optional.of(waypoint);
            }
        }
        return Optional.empty();
    }

    /** 按 id 或 name 解析（对外 API 的 nav_target_point 支持 id 与名称两种传值）。 */
    public Optional<WaypointMetadata> waypointByIdOrName(String floorId, String idOrName) {
        FloorGraph floorGraph = floorGraphs.get(floorId);
        if (floorGraph == null) {
            return Optional.empty();
        }
        for (WaypointMetadata waypoint : floorGraph.floor().getWaypoints()) {
            if (waypoint.getId().equals(idOrName)
                    || (waypoint.getName() != null && waypoint.getName().equals(idOrName))) {
                return Optional.of(waypoint);
            }
        }
        return Optional.empty();
    }

    /** 指定类型点位查询（如充电位 type=charge、停靠位 type=parking）。 */
    public List<WaypointMetadata> waypointsByType(String floorId, com.zioneer.robotscheduler.core.map.WaypointType type) {
        FloorGraph floorGraph = floorGraphs.get(floorId);
        if (floorGraph == null) {
            return List.of();
        }
        return floorGraph.floor().getWaypoints().stream()
                .filter(waypoint -> waypoint.getType() == type)
                .toList();
    }

    /** 是否存在点位（id 或 name）。 */
    public boolean hasWaypoint(String floorId, String idOrName) {
        return waypointByIdOrName(floorId, idOrName).isPresent();
    }

    /**
     * 规划：单层最短路（边权 = 段长 / min(段限速, 机器人速度)）。
     *
     * <p>封锁段/封锁点在搜索前从图中排除（而非事后检查最短路），保证绕行正确性。
     * 封锁点含静止机器人驻留点（场景：死端必经点被静止机器人堵住 → 无路可达 → 等待其离开）。</p>
     */
    /** 规划（五参便捷重载，无封锁点）。 */
    public Optional<NavigationRoute> plan(String floorId, String fromWaypointId, String toWaypointId,
                                          double robotMaxSpeed, Set<String> blockedSegmentIds) {
        return plan(floorId, fromWaypointId, toWaypointId, robotMaxSpeed, blockedSegmentIds, Set.of());
    }

    /** 规划（完整版）：封锁段/封锁点在搜索前从图排除；封锁点含静止机器人驻留点。
     *  起终点顶点永远保留（防机器人被自己的驻留点堵死：起点=自己的驻留点场景）。 */
    public Optional<NavigationRoute> plan(String floorId, String fromWaypointId, String toWaypointId,
                                          double robotMaxSpeed, Set<String> blockedSegmentIds,
                                          Set<String> blockedWaypointIds) {
        if (!Double.isFinite(robotMaxSpeed) || robotMaxSpeed <= 0) {
            throw new IllegalArgumentException("robotMaxSpeed must be finite and positive");
        }
        FloorGraph floorGraph = floorGraphs.get(floorId);
        if (floorGraph == null) {
            return Optional.empty();
        }
        boolean hasBlockedSegments = blockedSegmentIds != null && !blockedSegmentIds.isEmpty();
        boolean hasBlockedWaypoints = blockedWaypointIds != null && !blockedWaypointIds.isEmpty();
        if (!hasBlockedSegments && !hasBlockedWaypoints) {
            return planDirect(floorGraph, floorId, fromWaypointId, toWaypointId, robotMaxSpeed);
        }
        // 起终点强制保留（即使出现在封锁列表；原地任务 from==to 需去重防 Set.of 重复元素异常）
        Set<String> keepVertices = fromWaypointId.equals(toWaypointId)
                ? Set.of(fromWaypointId)
                : Set.of(fromWaypointId, toWaypointId);
        SimpleDirectedWeightedGraph<String, SegmentEdge> searchGraph =
                filterGraph(floorGraph.jgraph(), blockedSegmentIds, blockedWaypointIds, keepVertices);
        return planOnGraph(searchGraph, floorId, fromWaypointId, toWaypointId, robotMaxSpeed);
    }

    private Optional<NavigationRoute> planDirect(FloorGraph floorGraph, String floorId,
                                                 String fromWaypointId, String toWaypointId,
                                                 double robotMaxSpeed) {
        return planOnGraph(floorGraph.jgraph(), floorId, fromWaypointId, toWaypointId, robotMaxSpeed);
    }

    private Optional<NavigationRoute> planOnGraph(SimpleDirectedWeightedGraph<String, SegmentEdge> searchGraph,
                                                  String floorId, String fromWaypointId, String toWaypointId,
                                                  double robotMaxSpeed) {
        // 查询独立的权重视图，不修改共享图；搜索和 ETA 使用相同有效速度。
        var weightedGraph = new AsWeightedGraph<String, SegmentEdge>(searchGraph,
                edge -> edge.lengthMeters() / Math.min(edge.maxSpeed(), robotMaxSpeed), false, false);
        GraphPath<String, SegmentEdge> path = DijkstraShortestPath.findPathBetween(
                weightedGraph, fromWaypointId, toWaypointId);
        if (path == null) {
            return Optional.empty();
        }
        List<String> waypointIds = path.getVertexList();
        List<NavigationRoute.Segment> segments = new ArrayList<>();
        double totalLength = 0;
        double etaSeconds = 0;
        for (SegmentEdge edge : path.getEdgeList()) {
            double effectiveSpeed = Math.min(edge.maxSpeed(), robotMaxSpeed);
            NavigationRoute.Segment segment = new NavigationRoute.Segment(
                    edge.segmentId(), edge.pathId(), edge.fromWaypointId(), edge.toWaypointId(),
                    edge.lengthMeters(), effectiveSpeed, edge.obstacleAvoidance());
            segments.add(segment);
            totalLength += edge.lengthMeters();
            etaSeconds += edge.lengthMeters() / effectiveSpeed;
        }
        return Optional.of(new NavigationRoute(floorId, waypointIds, segments, totalLength, etaSeconds));
    }

    /** 构建排除封锁段/封锁点的搜索图；keepVertices 中的顶点强制保留（起终点）。 */
    private SimpleDirectedWeightedGraph<String, SegmentEdge> filterGraph(
            SimpleDirectedWeightedGraph<String, SegmentEdge> source, Set<String> blockedSegmentIds,
            Set<String> blockedWaypointIds, Set<String> keepVertices) {
        SimpleDirectedWeightedGraph<String, SegmentEdge> filtered =
                new SimpleDirectedWeightedGraph<>(SegmentEdge.class);
        Set<String> segments = blockedSegmentIds == null ? Set.of() : blockedSegmentIds;
        Set<String> waypoints = blockedWaypointIds == null ? Set.of() : blockedWaypointIds;
        Set<String> keep = keepVertices == null ? Set.of() : keepVertices;
        source.vertexSet().stream()
                .filter(vertex -> keep.contains(vertex) || !waypoints.contains(vertex))
                .forEach(filtered::addVertex);
        for (SegmentEdge edge : source.edgeSet()) {
            if (segments.contains(edge.segmentId())) {
                continue;
            }
            if (!filtered.containsVertex(edge.fromWaypointId()) || !filtered.containsVertex(edge.toWaypointId())) {
                continue;
            }
            filtered.addEdge(edge.fromWaypointId(), edge.toWaypointId(), edge);
            filtered.setEdgeWeight(edge, source.getEdgeWeight(edge));
        }
        return filtered;
    }

    /**
     * 位置吸附：优先 waypoint（阈值内），否则路径段垂距吸附。
     */
    public GraphAttachment attach(String floorId, double x, double y,
                                  double waypointThresholdMeters, double segmentThresholdMeters) {
        FloorGraph floorGraph = floorGraphs.get(floorId);
        if (floorGraph == null) {
            return GraphAttachment.none();
        }
        String bestWaypointId = null;
        double bestWaypointDistance = Double.MAX_VALUE;
        for (WaypointMetadata waypoint : floorGraph.floor().getWaypoints()) {
            double distance = Math.hypot(waypoint.getX() - x, waypoint.getY() - y);
            if (distance < bestWaypointDistance) {
                bestWaypointDistance = distance;
                bestWaypointId = waypoint.getId();
            }
        }
        if (bestWaypointDistance <= waypointThresholdMeters) {
            return new GraphAttachment(bestWaypointId, null, bestWaypointDistance);
        }
        String bestSegmentId = null;
        double bestSegmentDistance = Double.MAX_VALUE;
        for (SegmentEdge edge : floorGraph.jgraph().edgeSet()) {
            WaypointMetadata from = waypoint(floorId, edge.fromWaypointId()).orElse(null);
            WaypointMetadata to = waypoint(floorId, edge.toWaypointId()).orElse(null);
            if (from == null || to == null) {
                continue;
            }
            double distance = GeometryUtil.pointToSegmentDistance(x, y,
                    from.getX(), from.getY(), to.getX(), to.getY());
            if (distance < bestSegmentDistance) {
                bestSegmentDistance = distance;
                bestSegmentId = edge.segmentId();
            }
        }
        if (bestSegmentId != null && bestSegmentDistance <= segmentThresholdMeters) {
            return new GraphAttachment(null, bestSegmentId, bestSegmentDistance);
        }
        return GraphAttachment.none();
    }

    private record FloorGraph(SimpleDirectedWeightedGraph<String, SegmentEdge> jgraph, FloorMetadata floor) {
    }

    /** 段边解析（路段中间起步场景：吸附到段但不在任何点阈值内时，取段端点作为规划起点）。 */
    public Optional<SegmentEdge> segmentEdge(String floorId, String segmentId) {
        FloorGraph floorGraph = floorGraphs.get(floorId);
        if (floorGraph == null || segmentId == null) {
            return Optional.empty();
        }
        return floorGraph.jgraph().edgeSet().stream()
                .filter(edge -> segmentId.equals(edge.segmentId()))
                .findFirst();
    }

    /** 图边：携带路径段调度属性。 */
    public record SegmentEdge(String segmentId, String pathId, String fromWaypointId, String toWaypointId,
                              double lengthMeters, double maxSpeed, boolean obstacleAvoidance) {
    }

    /** 跨层连接索引（向后兼容预留）。 */
    public Map<String, List<String>> interFloorEndpointIndex() {
        Map<String, List<String>> index = new HashMap<>();
        for (var connection : source.getInterFloorConnections()) {
            for (var endpoint : connection.getConnections()) {
                index.computeIfAbsent(endpoint.getFloorId(), k -> new ArrayList<>())
                        .add(endpoint.getWaypointId());
            }
        }
        return index;
    }
}
