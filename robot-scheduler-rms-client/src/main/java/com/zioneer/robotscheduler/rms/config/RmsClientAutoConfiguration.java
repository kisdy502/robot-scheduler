package com.zioneer.robotscheduler.rms.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zioneer.robotscheduler.core.fleet.RobotGateway;
import com.zioneer.robotscheduler.core.fleet.RobotStateSource;
import com.zioneer.robotscheduler.rms.client.HttpRobotManagementClient;
import com.zioneer.robotscheduler.rms.client.MockRobotManagementClient;
import com.zioneer.robotscheduler.rms.client.RobotManagementClient;
import com.zioneer.robotscheduler.rms.gateway.RmsRobotGateway;
import com.zioneer.robotscheduler.rms.gateway.RmsRobotStateSource;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestClient;

/**
 * RMS 客户端自动装配：默认 HTTP 实现；robot.scheduler.rms.mock-enabled=true 时切换 Mock。
 */
@AutoConfiguration
@EnableConfigurationProperties(RobotManagementProperties.class)
public class RmsClientAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(RobotManagementClient.class)
    public RobotManagementClient robotManagementClient(RobotManagementProperties properties,
                                                       RestClient.Builder restClientBuilder,
                                                       ObjectMapper objectMapper) {
        if (properties.isMockEnabled()) {
            return new MockRobotManagementClient();
        }
        // 超时工厂（connect/read 按 properties）：无超时请求 hang 会冻结 1Hz 状态轮询
        return new HttpRobotManagementClient(properties, restClientBuilder, objectMapper,
                HttpRobotManagementClient.defaultRequestFactory(properties));
    }

    @Bean
    @ConditionalOnMissingBean(RobotGateway.class)
    public RobotGateway robotGateway(RobotManagementClient client) {
        return new RmsRobotGateway(client);
    }

    @Bean
    @ConditionalOnMissingBean(RobotStateSource.class)
    public RobotStateSource robotStateSource(RobotManagementClient client, RobotManagementProperties properties) {
        return new RmsRobotStateSource(client, properties);
    }
}
