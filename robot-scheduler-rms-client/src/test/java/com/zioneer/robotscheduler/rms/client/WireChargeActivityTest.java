package com.zioneer.robotscheduler.rms.client;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 充电动作线上模板标识：机器人侧按模板 code（服务器创建模板生成的唯一号，如
 * auto-goto-charge101）查模板，不是固定类型 charge——优先取任务流传入的
 * task_template_code，无 code 时用配置兜底；非充电动作原样透传。
 * description.task_template_type 为固定类型语义（恒 charge），不做改写。
 */
class WireChargeActivityTest {

    @Test
    void chargeActivity_usesTaskflowTemplateCode() {
        Map<String, Object> description = new HashMap<>(Map.of(
                "task_template_code", "auto-goto-charge101",
                "task_template_type", "charge"));

        assertThat(HttpRobotManagementClient.wireChargeActivity("charge", description, "back-charge"))
                .isEqualTo("auto-goto-charge101");
    }

    @Test
    void chargeActivity_blankCode_fallsBackToConfigured() {
        Map<String, Object> description = new HashMap<>(Map.of("task_template_type", "charge"));

        assertThat(HttpRobotManagementClient.wireChargeActivity("charge", description, "back-charge"))
                .isEqualTo("back-charge");
    }

    @Test
    void chargeActivity_noCodeNoConfig_passThrough() {
        assertThat(HttpRobotManagementClient.wireChargeActivity("charge", null, null))
                .isEqualTo("charge");
        assertThat(HttpRobotManagementClient.wireChargeActivity("charge", Map.of(), " "))
                .isEqualTo("charge");
    }

    @Test
    void nonChargeActivity_passThrough() {
        Map<String, Object> description = new HashMap<>(Map.of(
                "task_template_code", "QC_TEMPLATE_A",
                "task_template_type", "quality_inspection"));

        assertThat(HttpRobotManagementClient.wireChargeActivity("quality_inspection", description, "back-charge"))
                .isEqualTo("quality_inspection");
    }

    @Test
    void blankTemplateCodeIgnored() {
        Map<String, Object> description = new HashMap<>(Map.of("task_template_code", " "));

        assertThat(HttpRobotManagementClient.wireChargeActivity("charge", description, "back-charge"))
                .isEqualTo("back-charge");
    }
}
