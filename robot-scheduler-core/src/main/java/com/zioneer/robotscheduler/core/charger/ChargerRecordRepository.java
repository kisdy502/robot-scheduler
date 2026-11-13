package com.zioneer.robotscheduler.core.charger;

import java.util.List;
import java.util.Optional;

/**
 * 充电桩记录仓储端口（persistence 提供 MyBatis-Plus 实现；t_charger 表为正本）。
 */
public interface ChargerRecordRepository {

    List<ChargerInfo> findAll();

    Optional<ChargerInfo> findByChargerId(String chargerId);

    /** 按 charger_id 插入或更新（地图对账用，保留动态占用字段由调用方处理）。 */
    void upsert(ChargerInfo charger);

    /** 更新动态占用字段（status/occupied_by/reserved_by）。 */
    void updateOccupancy(ChargerInfo charger);

    /** 对账停用：不在给定集合内的桩置 enabled=false（不软删——软删行与新图重名桩撞物理唯一键）。 */
    int disableByChargerIdsNotIn(List<String> keepChargerIds);
}
