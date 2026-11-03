package com.zioneer.robotscheduler.core.repository;

import java.util.List;

/**
 * 外部动作回执镜像仓储端口（观测档案读写，实现放 persistence）。
 */
public interface ExternalActivityRepository {

    /** 幂等判断：该 RMS 回执是否已存档。 */
    boolean existsByRmsCommandId(String rmsCommandId);

    void insert(ExternalActivityRecord record);

    List<ExternalActivityRecord> findPage(String robotCode, String bodyTaskId, int offset, int limit);

    long count(String robotCode, String bodyTaskId);
}
