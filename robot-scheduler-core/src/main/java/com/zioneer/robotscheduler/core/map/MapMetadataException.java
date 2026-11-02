package com.zioneer.robotscheduler.core.map;

/**
 * 地图元数据异常（解析/校验失败、非法激活操作等）。
 */
public class MapMetadataException extends RuntimeException {

    public MapMetadataException(String message) {
        super(message);
    }

    public MapMetadataException(String message, Throwable cause) {
        super(message, cause);
    }
}
