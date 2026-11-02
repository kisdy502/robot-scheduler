package com.zioneer.robotscheduler;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 机器人调度服务启动类。
 *
 * <p>包根扫描覆盖各模块组件（core/@Component、persistence/@Repository、api/@RestController）；
 * 模块内 @AutoConfiguration 通过 AutoConfiguration.imports 注册。
 * @EnableScheduling 驱动 RMS 轮询（FleetManager）与 stale 扫描。</p>
 */
@SpringBootApplication
@EnableScheduling
public class RobotSchedulerApplication {

    public static void main(String[] args) {
        SpringApplication.run(RobotSchedulerApplication.class, args);
    }
}
