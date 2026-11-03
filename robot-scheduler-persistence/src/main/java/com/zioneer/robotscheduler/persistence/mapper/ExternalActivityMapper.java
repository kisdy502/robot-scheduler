package com.zioneer.robotscheduler.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zioneer.robotscheduler.persistence.entity.ExternalActivityEntity;
import org.apache.ibatis.annotations.Mapper;

/** 外部动作镜像表 Mapper（MyBatis-Plus BaseMapper，禁止 XML）。 */
@Mapper
public interface ExternalActivityMapper extends BaseMapper<ExternalActivityEntity> {
}
