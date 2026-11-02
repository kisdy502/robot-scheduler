package com.zioneer.robotscheduler.core.map;

import com.zioneer.robotscheduler.common.core.error.BusinessException;
import com.zioneer.robotscheduler.common.core.error.ErrorCode;

/** 地图尚被任务或交通资源使用，拒绝切换。 */
public class MapActivationConflictException extends BusinessException {
    public MapActivationConflictException(String message) {
        super(ErrorCode.SCHEDULER_MAP_SWITCH_CONFLICT, message);
    }
}
