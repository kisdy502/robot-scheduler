package com.zioneer.robotscheduler.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Schema(description = "外部作业通知；operation_id 在该机器人上标识一次业务作业，重试保持不变")
public class ExternalOperationRequest {
    @NotBlank
    @Schema(description = "持有机器人使用权的任务流 ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private String taskflowId;
    @NotBlank
    @Schema(description = "机器人编码", requiredMode = Schema.RequiredMode.REQUIRED)
    private String robotCode;
    @NotBlank
    @Schema(description = "本次外部作业唯一 ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private String operationId;
}
