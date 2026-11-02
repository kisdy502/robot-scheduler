package com.zioneer.robotscheduler.core.repository;

import java.util.List;
import java.util.Optional;

/**
 * 地图存档仓储端口（persistence 提供 MyBatis-Plus 实现）。
 */
public interface MapArchiveRepository {

    void save(MapArchive archive);

    void update(MapArchive archive);

    /** 当前激活版本。 */
    Optional<MapArchive> findActive();

    /** 按地图名+版本查询。 */
    Optional<MapArchive> findByMapNameAndVersion(String mapName, String version);

    /** 指定地图的全部版本（新→旧）。 */
    List<MapArchive> findByMapName(String mapName);
}
