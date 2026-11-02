package com.zioneer.robotscheduler.core.traffic;

import java.util.List;

/**
 * 交通事件审计端口（占用/释放/清理），由 persistence 实现写 t_traffic_event。
 */
public interface TrafficEventSink {

    void record(String robotCode, String action, List<String> resourceIds, String detail);

    /** 空实现（测试/未装配时）。 */
    TrafficEventSink NOOP = (robotCode, action, resourceIds, detail) -> {
    };
}
