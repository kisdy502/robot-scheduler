package com.zioneer.robotscheduler.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zioneer.robotscheduler.persistence.entity.RobotRuntimeEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface RobotRuntimeMapper extends BaseMapper<RobotRuntimeEntity> {
}
