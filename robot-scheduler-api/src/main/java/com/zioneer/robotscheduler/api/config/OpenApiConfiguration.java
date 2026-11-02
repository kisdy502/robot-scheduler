package com.zioneer.robotscheduler.api.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 元信息（springdoc）。
 */
@Configuration
public class OpenApiConfiguration {

    @Bean
    public OpenAPI schedulerOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Robot Scheduler REST API")
                .description("机器人调度服务（Java 重构版），接口字段与旧 robot-scheduler-rest-api 0.2.0 保持一致")
                .version("0.2.0"));
    }
}
