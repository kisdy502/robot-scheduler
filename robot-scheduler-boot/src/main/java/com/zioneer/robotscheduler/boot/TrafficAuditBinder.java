package com.zioneer.robotscheduler.boot;

import com.zioneer.robotscheduler.core.traffic.TrafficRegistry;
import com.zioneer.robotscheduler.persistence.repository.DbTrafficEventSink;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import jakarta.annotation.PostConstruct;

/**
 * 交通审计装配：TrafficRegistry → t_traffic_event。
 */
@Configuration
@RequiredArgsConstructor
public class TrafficAuditBinder {

    private final TrafficRegistry trafficRegistry;
    private final DbTrafficEventSink trafficEventSink;

    @PostConstruct
    void bind() {
        if (trafficRegistry instanceof com.zioneer.robotscheduler.core.traffic.InMemoryTrafficRegistry inMemory) {
            inMemory.setEventSink(trafficEventSink);
        }
    }
}
