package com.zioneer.robotscheduler.core.assign;

import com.zioneer.robotscheduler.core.fleet.RobotSnapshot;
import lombok.RequiredArgsConstructor;

/**
 * 充电中断策略：充电中机器人能否被打断去执行任务，以及打断的代价。
 *
 * <p>不是按"充电状态/固定电量阈值"一刀切，而是评估<b>中断的安全性</b>：
 * 预计任务耗电后，电量仍需高于（返充线 + 余量），即"做完这单也不会陷入低电"。
 * 安全性由任务 ETA 与放电速率共同决定——近任务容易放行、远任务门槛自动抬高。</p>
 *
 * <p>全部数值是策略参数（配置），后续按运营经验调整或整体替换实现：</p>
 * <ul>
 *   <li>返充线：{@code low-battery-threshold-percent}（与电量惩罚共用，30%）</li>
 *   <li>安全余量：{@code charging-interruption-margin-percent}（默认 10pp）</li>
 *   <li>放电速率：{@code battery-drain-percent-per-minute}（默认 0.5%/min）</li>
 *   <li>打断代价：{@code charging-interrupt-penalty-seconds}（默认 120s，竞争时空闲机优先）</li>
 * </ul>
 */
@RequiredArgsConstructor
public class ChargingInterruptionPolicy {

    private final TaskAssignProperties properties;

    /**
     * 充电中的机器人现在中断去执行预计耗时 taskEtaSeconds 的任务是否安全。
     * 电量未知 = 不安全（保守，无法评估剩余电量）。
     */
    public boolean interruptible(RobotSnapshot robot, double taskEtaSeconds) {
        Double battery = robot.getBattery();
        if (battery == null) {
            return false;
        }
        double projectedAfterTask = battery - drainPercent(taskEtaSeconds);
        return projectedAfterTask >= properties.getLowBatteryThresholdPercent()
                + properties.getChargingInterruptionMarginPercent();
    }

    /** 中断充电参与竞争的额外代价（秒）：同等条件空闲机优先，充电机需有明显距离优势。 */
    public double penaltySeconds() {
        return properties.getChargingInterruptPenaltySeconds();
    }

    /** 任务预计耗电（百分比）：按放电速率把 ETA 折算成电量消耗。 */
    private double drainPercent(double taskEtaSeconds) {
        return taskEtaSeconds / 60.0 * properties.getBatteryDrainPercentPerMinute();
    }
}
