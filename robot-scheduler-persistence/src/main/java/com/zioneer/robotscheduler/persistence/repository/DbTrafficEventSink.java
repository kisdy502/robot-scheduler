package com.zioneer.robotscheduler.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zioneer.robotscheduler.core.traffic.TrafficEventSink;
import com.zioneer.robotscheduler.persistence.entity.TrafficEventEntity;
import com.zioneer.robotscheduler.persistence.mapper.TrafficEventMapper;
import com.zioneer.robotscheduler.persistence.support.SnowflakeIdGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 交通事件审计 DB 实现：占用/释放/清理写入 t_traffic_event + 分页查询。
 */
@Component
@RequiredArgsConstructor
public class DbTrafficEventSink implements TrafficEventSink {

    private final TrafficEventMapper mapper;
    private final SnowflakeIdGenerator idGenerator;

    @Override
    public void record(String robotCode, String action, List<String> resourceIds, String detail) {
        TrafficEventEntity entity = new TrafficEventEntity();
        entity.setId(idGenerator.nextId());
        entity.setRobotCode(robotCode);
        entity.setAction(action);
        // 整条路线保留完整资源列表，长度由 TEXT 列承载，不能截断影响排障。
        entity.setResourceId(resourceIds == null || resourceIds.isEmpty() ? null
                : String.join(",", resourceIds));
        entity.setDetail(detail == null ? Map.of() : Map.of("message", detail));
        entity.setCreatedAt(Instant.now());
        mapper.insert(entity);
    }

    /** 分页查询（更新时间倒序）。 */
    public List<TrafficEventEntity> findPage(String robotCode, String action, int pageNum, int pageSize) {
        Page<TrafficEventEntity> page = new Page<>(pageNum, pageSize, false);
        LambdaQueryWrapper<TrafficEventEntity> wrapper = new LambdaQueryWrapper<TrafficEventEntity>()
                .orderByDesc(TrafficEventEntity::getId);
        if (robotCode != null && !robotCode.isBlank()) {
            wrapper.eq(TrafficEventEntity::getRobotCode, robotCode);
        }
        if (action != null && !action.isBlank()) {
            wrapper.eq(TrafficEventEntity::getAction, action);
        }
        return mapper.selectPage(page, wrapper).getRecords();
    }

    public long count(String robotCode, String action) {
        LambdaQueryWrapper<TrafficEventEntity> wrapper = new LambdaQueryWrapper<>();
        if (robotCode != null && !robotCode.isBlank()) {
            wrapper.eq(TrafficEventEntity::getRobotCode, robotCode);
        }
        if (action != null && !action.isBlank()) {
            wrapper.eq(TrafficEventEntity::getAction, action);
        }
        return mapper.selectCount(wrapper);
    }
}
