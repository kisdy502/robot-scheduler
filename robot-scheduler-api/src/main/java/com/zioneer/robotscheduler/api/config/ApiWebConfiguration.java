package com.zioneer.robotscheduler.api.config;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 层配置：JSON 全局 snake_case（对齐旧 API 字段命名与平台规范）+
 * 入站访问日志（低频端点全量 INFO，轮询端点排除）。
 *
 * <p>facade 返回的 Map key 已是 snake_case 字符串，不受命名策略影响；
 * 本策略主要作用于 DTO 属性与查询对象。</p>
 */
@Configuration
@EnableConfigurationProperties(AccessLogProperties.class)
public class ApiWebConfiguration implements WebMvcConfigurer {

    private final AccessLogProperties accessLogProperties;

    public ApiWebConfiguration(AccessLogProperties accessLogProperties) {
        this.accessLogProperties = accessLogProperties;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        if (accessLogProperties.isEnabled()) {
            registry.addInterceptor(new AccessLogInterceptor(accessLogProperties.getExcludePaths()))
                    .addPathPatterns("/api/v1/**");
        }
    }

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer schedulerJsonCustomizer() {
        return builder -> builder.propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    }
}
