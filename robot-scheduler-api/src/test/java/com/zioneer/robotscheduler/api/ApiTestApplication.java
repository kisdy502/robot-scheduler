package com.zioneer.robotscheduler.api;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * API 模块切片测试的启动配置（@WebMvcTest 需要包根上存在带组件扫描的配置类；
 * 正式启动类在 boot 模块，api 模块测试上下文不可见，故提供测试专用入口，
 * @WebMvcTest 的切片过滤器只保留 Controller/ControllerAdvice/WebMvcConfigurer 相关 bean）。
 */
@SpringBootApplication
public class ApiTestApplication {
}
