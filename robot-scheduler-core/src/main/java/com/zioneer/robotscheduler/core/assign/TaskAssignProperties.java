package com.zioneer.robotscheduler.core.assign;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 任务分配配置。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "robot.scheduler.assign")
public class TaskAssignProperties {

    /** 调度导航速度上限（m/s）；机器人协议尚无逐机速度能力，分配与执行共用此值。 */
    private double navigationMaxSpeedMps = 1.0;

    public void setNavigationMaxSpeedMps(double speed) {
        if (!Double.isFinite(speed) || speed <= 0) {
            throw new IllegalArgumentException("navigationMaxSpeedMps must be finite and positive");
        }
        this.navigationMaxSpeedMps = speed;
    }

    /** 低电量惩罚起点（百分比，低于开始线性惩罚）。 */
    private double lowBatteryThresholdPercent = 30.0;

    /** 电量惩罚权重（BatteryAware：低电量的 ETA 等效秒惩罚系数）。 */
    private double batteryPenaltyWeightSeconds = 120.0;

    /** 忙碌惩罚（机器人正在执行任务时增加的固定代价秒）。 */
    private double busyPenaltySeconds = 300.0;

    /**
     * 充电中断安全余量（百分点）：充电机中断执行任务后，预计电量需 ≥（返充线 + 此值）
     * 才允许放行。策略参数，随运营调整。
     */
    private double chargingInterruptionMarginPercent = 10.0;

    /** 充电打断代价（秒）：可中断的充电机参与竞争的额外代价——同等条件空闲机优先。 */
    private double chargingInterruptPenaltySeconds = 120.0;

    /**
     * 放电速率（百分比/分钟）：把任务 ETA 折算成预计耗电，用于充电中断安全性评估。
     * 策略参数（典型 SLAM 导航车巡航 0.3~0.8%/min）。
     */
    private double batteryDrainPercentPerMinute = 0.5;

    /** 无可用机器人时任务的等待上限（秒，超时 failed）。 */
    private long assignmentTimeoutSeconds = 60;
}
