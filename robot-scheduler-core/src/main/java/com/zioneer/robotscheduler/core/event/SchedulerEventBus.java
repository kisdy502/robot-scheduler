package com.zioneer.robotscheduler.core.event;

import com.zioneer.robotscheduler.core.task.TaskStateEvent;
import org.springframework.context.ApplicationEventPublisher;

/**
 * 调度内核事件总线（替代 ROS2 topic）：基于 Spring 事件同步发布，
 * 订阅方（快照镜像、Redis 推送、日志审计）自行监听。
 *
 * <p>契约保持与旧 task_state_update JSON 同构，便于联调期做日志比对。</p>
 */
public class SchedulerEventBus {

    private final ApplicationEventPublisher publisher;

    public SchedulerEventBus(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    public void publishTaskState(TaskStateEvent event) {
        publisher.publishEvent(event);
    }
}
