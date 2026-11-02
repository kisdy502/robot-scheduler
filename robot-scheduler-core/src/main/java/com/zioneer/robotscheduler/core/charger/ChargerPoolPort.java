package com.zioneer.robotscheduler.core.charger;

import java.util.List;
import java.util.Optional;

/**
 * 充电桩池端口（对齐原 charger_pool_manager 的 allocate/release/list 三个服务语义）。
 */
public interface ChargerPoolPort {

    /**
     * 分配充电桩：优先从候选列表选择，无候选或候选全忙时按兼容性自动兜底。
     *
     * @param robotCode             机器人编码
     * @param fleetName             车队名
     * @param chargerWaypointList   候选桩 waypoint 列表（可为空=自动）
     * @param resourceType          charger / swap_station / null=不限
     * @return 分配结果；无可用桩返回空（由 API 层转 409）
     */
    Optional<ChargerInfo> allocate(String robotCode, String fleetName,
                                   List<String> chargerWaypointList, String resourceType);

    /**
     * 释放充电桩（按机器人或按桩，二者至少一个非空）。
     */
    boolean release(String robotCode, String chargerWaypoint, String resourceType);

    /**
     * 驻留占用登记：机器人物理停在桩位上（交通驻留真相同步到桩池，
     * 保证 Chargers 页显示与选桩分配不与实际占用矛盾）。幂等。
     *
     * @return true=登记成功（桩存在且未被其他机器人占用）
     */
    boolean occupy(String robotCode, String chargerWaypoint);

    /**
     * 查询充电桩列表（可按车队过滤）。
     */
    List<ChargerInfo> list(String fleetName);
}
