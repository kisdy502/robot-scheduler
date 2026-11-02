package com.zioneer.robotscheduler.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zioneer.robotscheduler.persistence.entity.TaskRecordEntity;

/**
 * 调度请求记录 Mapper：仅使用 MyBatis-Plus 通用方法 + Lambda 条件构造器，禁止手写业务 SQL。
 */
public interface TaskRecordMapper extends BaseMapper<TaskRecordEntity> {
}
