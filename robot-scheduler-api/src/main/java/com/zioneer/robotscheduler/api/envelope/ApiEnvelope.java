package com.zioneer.robotscheduler.api.envelope;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import lombok.Getter;
import lombok.Setter;

/**
 * 对外调度端点统一响应信封（与旧 robot-scheduler-rest-api 0.2.0 保持字段一模一样）：
 * {"status":0,"msg":"success","data":...,"timestamp":&lt;unix毫秒&gt;}。
 *
 * <p>与内部 Result 的差异：多 timestamp 字段、msg 有固定业务文案（如 request published; execution pending）。
 * 错误响应不使用本信封，而是 FastAPI 风格 {"detail":...}（见 ApiExceptionHandler）。</p>
 */
@Getter
@Setter
@JsonInclude(JsonInclude.Include.ALWAYS)
@JsonPropertyOrder({"status", "msg", "data", "timestamp"})
public class ApiEnvelope<T> {

    /** 0 表示成功（与旧契约一致）。 */
    private int status;

    private String msg;

    private T data;

    /** unix 毫秒（旧契约为 int）。 */
    private long timestamp;

    public static <T> ApiEnvelope<T> ok(T data) {
        return build(0, "success", data);
    }

    public static <T> ApiEnvelope<T> published(T data) {
        // 调度类请求发布成功的固定文案，上层依赖该文案判断"已受理"
        return build(0, com.zioneer.robotscheduler.common.scheduler.SchedulerConstants.REQUEST_PUBLISHED_MESSAGE, data);
    }

    public static <T> ApiEnvelope<T> build(int status, String msg, T data) {
        ApiEnvelope<T> envelope = new ApiEnvelope<>();
        envelope.setStatus(status);
        envelope.setMsg(msg);
        envelope.setData(data);
        envelope.setTimestamp(System.currentTimeMillis());
        return envelope;
    }
}
