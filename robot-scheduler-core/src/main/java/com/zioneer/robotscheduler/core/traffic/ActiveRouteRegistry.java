package com.zioneer.robotscheduler.core.traffic;

import com.zioneer.robotscheduler.core.map.MapManager;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 活动路线注册表：机器人当前任务规划路线的有序快照（前端可视化数据源）。
 *
 * <p>生命周期与任务租约一致：TaskExecutionEngine 获取交通租约成功时 register（整条途经点序列），
 * 分段导航每到达一个途经点 advance（进度推进），任务终态/取消/机器人下线时 clear。
 * 内存态（随任务生灭的瞬时数据，不落库）；历史审计走任务终态事件的 detail.route_waypoints。</p>
 */
@Component
public class ActiveRouteRegistry {

    private final MapManager mapManager;
    private final Map<String, ActiveRoute> routesByRobot = new ConcurrentHashMap<>();

    public ActiveRouteRegistry(MapManager mapManager) {
        this.mapManager = mapManager;
    }

    /** 注册/覆盖机器人路线（同机新任务或停靠段导航会覆盖旧路线）。 */
    public void register(String robotCode, String taskId, String floorId, List<String> waypointIds) {
        List<RouteWaypoint> waypoints = new ArrayList<>(waypointIds.size());
        var graph = mapManager.activeGraph().orElse(null);
        for (String id : waypointIds) {
            Double x = null;
            Double y = null;
            if (graph != null && graph.isLoaded()) {
                var waypoint = graph.waypoint(floorId, id)
                        .or(() -> graph.waypointByIdOrName(floorId, id))
                        .orElse(null);
                if (waypoint != null) {
                    x = waypoint.getX();
                    y = waypoint.getY();
                }
            }
            waypoints.add(new RouteWaypoint(id, x, y));
        }
        routesByRobot.put(robotCode, new ActiveRoute(robotCode, taskId, floorId,
                List.copyOf(waypoints), 0, System.currentTimeMillis()));
    }

    /** 机器人到达某途经点后推进进度（已到达索引）。 */
    public void advance(String robotCode, String reachedWaypointId) {
        ActiveRoute route = routesByRobot.get(robotCode);
        if (route == null) {
            return;
        }
        for (int i = 0; i < route.waypoints().size(); i++) {
            if (route.waypoints().get(i).id().equals(reachedWaypointId)) {
                if (i > route.progressIndex()) {
                    routesByRobot.put(robotCode, new ActiveRoute(robotCode, route.taskId(),
                            route.floorId(), route.waypoints(), i, System.currentTimeMillis()));
                }
                return;
            }
        }
    }

    /** 清除机器人路线（任务结束/取消/下线）。 */
    public void clear(String robotCode) {
        routesByRobot.remove(robotCode);
    }

    /** 清空全部路线（地图热切换：旧图点位坐标失效）。 */
    public void clearAll() {
        routesByRobot.clear();
    }

    /** 当前全部活动路线（按机器人排序，快照只读）。 */
    public List<ActiveRoute> snapshot() {
        return routesByRobot.values().stream()
                .sorted(Comparator.comparing(ActiveRoute::robotCode))
                .toList();
    }

    /** 路线途经点（坐标在注册时从活动地图解析；无图/点位缺失时为 null）。 */
    public record RouteWaypoint(String id, Double x, Double y) {
    }

    /** 机器人活动路线。 */
    public record ActiveRoute(String robotCode, String taskId, String floorId,
                              List<RouteWaypoint> waypoints, int progressIndex,
                              long updatedAtMillis) {
    }
}
