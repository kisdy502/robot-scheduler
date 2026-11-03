package com.zioneer.robotscheduler.api.contract;

import com.zioneer.robotscheduler.api.config.ApiWebConfiguration;
import com.zioneer.robotscheduler.api.controller.RuntimeController;
import com.zioneer.robotscheduler.api.controller.SchedulerResourceController;
import com.zioneer.robotscheduler.api.controller.SchedulerTaskController;
import com.zioneer.robotscheduler.core.dispatch.SchedulerFacade;
import com.zioneer.robotscheduler.core.dispatch.SchedulerHttpException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 调度端点 HTTP 契约测试（对齐旧栈 envelope / FastAPI detail 错误格式 / snake_case 字段）。
 */
@WebMvcTest(controllers = {SchedulerTaskController.class, SchedulerResourceController.class,
        RuntimeController.class, com.zioneer.robotscheduler.api.controller.RobotUseController.class})
@Import(ApiWebConfiguration.class)
class SchedulerApiContractTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private SchedulerFacade schedulerFacade;

    // 新流程的幂等选项按原 parameters 透传，成功文案及冲突错误信封仍遵循旧接口契约。
    @Test
    void idempotentMove_preservesPublishedEnvelopeAnd409Detail() throws Exception {
        when(schedulerFacade.gotoPlace(anyString(), anyString(), anyString(), anyString(), any(),
                any(), any(), any(), any())).thenReturn(Map.of("scheduler_request_id", "same-request"))
                .thenThrow(SchedulerHttpException.conflict("task_id already exists with a different request"));
        String body = """
                {"taskflow_id":1001,"task_id":2001,"robot_code":"R1","nav_target_point":"W1",
                 "parameters":{"idempotent":true}}
                """;
        mockMvc.perform(post("/api/v1/scheduler/goto-place")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.msg").value("request published; execution pending"))
                .andExpect(jsonPath("$.data.scheduler_request_id").value("same-request"));
        verify(schedulerFacade).gotoPlace(eq("1001"), eq("2001"), eq("R1"), eq("W1"), any(),
                any(), any(), any(), eq(Map.of("idempotent", true)));
        mockMvc.perform(post("/api/v1/scheduler/goto-place")
                        .contentType(MediaType.APPLICATION_JSON).content(body.replace("W1", "W2")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("task_id already exists with a different request"));
    }

    @Nested
    class ExternalOperationContract {
        private final String body = "{\"taskflow_id\":\"1001\",\"robot_code\":\"R1\",\"operation_id\":\"photo-1\"}";

        // 外部作业结束未能持久化时返回 503 detail，不能包装成已经释放占用的成功响应。
        @Test
        void operationPersistenceFailureReturns503Detail() throws Exception {
            when(schedulerFacade.finishExternalOperation("1001", "R1", "photo-1"))
                    .thenThrow(new SchedulerHttpException(503, "robot usage could not be persisted"));
            mockMvc.perform(post("/api/v1/scheduler/external-operations/finish")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.detail").value("robot usage could not be persisted"))
                    .andExpect(jsonPath("$.status").doesNotExist());
        }

        // 外部作业开始和结束接口沿用旧成功信封，时间字段必须返回 Unix 毫秒。
        @Test
        void startAndFinishUseLegacyEnvelopeAndUnixMillis() throws Exception {
            when(schedulerFacade.startExternalOperation("1001", "R1", "photo-1"))
                    .thenReturn(Map.of("robot_code", "R1", "state", "running", "started_at", 1793613600000L));
            mockMvc.perform(post("/api/v1/scheduler/external-operations/start")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.status").value(0))
                    .andExpect(jsonPath("$.msg").value("success"))
                    .andExpect(jsonPath("$.data.started_at").value(1793613600000L))
                    .andExpect(jsonPath("$.timestamp").isNumber());
            when(schedulerFacade.finishExternalOperation("1001", "R1", "photo-1"))
                    .thenReturn(Map.of("state", "finished", "finished_at", 1793613601000L));
            mockMvc.perform(post("/api/v1/scheduler/external-operations/finish")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.state").value("finished"))
                    .andExpect(jsonPath("$.data.finished_at").value(1793613601000L));
        }

        // 缺少必填字段返回 400，使用权冲突返回 409，并保持 FastAPI 风格 detail 错误结构。
        @Test
        void invalidOperationIs400AndOwnershipConflictIs409Detail() throws Exception {
            mockMvc.perform(post("/api/v1/scheduler/external-operations/start")
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").exists());
            when(schedulerFacade.startExternalOperation("1001", "R1", "photo-1"))
                    .thenThrow(SchedulerHttpException.conflict("robot owned by another taskflow"));
            mockMvc.perform(post("/api/v1/scheduler/external-operations/start")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.detail").value("robot owned by another taskflow"));
        }

        // 通过独立使用状态接口查询机器人和持有者，响应沿用现有成功信封。
        @Test
        void usageCanBeQueriedWithoutChangingLegacyResourceResponse() throws Exception {
            when(schedulerFacade.robotUse("R1"))
                    .thenReturn(Map.of("robot_code", "R1", "taskflow_id", "1001"));
            mockMvc.perform(get("/api/v1/scheduler/robot-use").param("robot_code", "R1"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.taskflow_id").value("1001"));
        }
    }

    @Nested
    class EnvelopeContract {

        @Test
        void assignTask_success_returnsEnvelopeWithTimestampAndSnakeCaseFields() throws Exception {
            when(schedulerFacade.assignTask(anyString(), any(), anyString(), any(), any(),
                    any(), any())).thenReturn(Map.of("robot_code", "R009"));

            mockMvc.perform(post("/api/v1/scheduler/assign-task")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"taskflow_id": 1001, "robot_code": "R009", "nav_point_start": "P001"}
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value(0))
                    .andExpect(jsonPath("$.msg").value("success"))
                    .andExpect(jsonPath("$.data.robot_code").value("R009"))
                    .andExpect(jsonPath("$.timestamp").isNumber());

            // 请求体 snake_case 绑定到 camelCase DTO 的验证
            ArgumentCaptor<String> taskflowId = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> robotCode = ArgumentCaptor.forClass(String.class);
            verify(schedulerFacade).assignTask(taskflowId.capture(), any(), robotCode.capture(),
                    any(), any(), any(), any());
            assertThat(taskflowId.getValue()).isEqualTo("1001");
            assertThat(robotCode.getValue()).isEqualTo("R009");
        }

        @Test
        void gotoPlace_success_returnsPublishedMessage() throws Exception {
            when(schedulerFacade.gotoPlace(anyString(), anyString(), any(), anyString(), any(), any(),
                    any(), any(), any()))
                    .thenReturn(Map.of("dispatch_status", "published", "task_status", "queued"));

            mockMvc.perform(post("/api/v1/scheduler/goto-place")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"taskflow_id": 1001, "task_id": 2001, "robot_code": "R001",
                                     "nav_target_point": "inspection_start_1"}
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value(0))
                    .andExpect(jsonPath("$.msg").value("request published; execution pending"))
                    .andExpect(jsonPath("$.data.dispatch_status").value("published"))
                    .andExpect(jsonPath("$.data.task_status").value("queued"));
        }

        @Test
        void gotoPlace_missingNavTargetPoint_returns400Detail() throws Exception {
            mockMvc.perform(post("/api/v1/scheduler/goto-place")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"taskflow_id": 1001, "task_id": 2001}
                                    """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail").exists())
                    .andExpect(jsonPath("$.status").doesNotExist());
        }

        @Test
        void dispatchTask_nullDataEnvelopeFieldIsAlwaysPresent() throws Exception {
            when(schedulerFacade.releaseResources("R001")).thenReturn(null);

            mockMvc.perform(post("/api/v1/scheduler/release-resources")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"robot_code": "R001"}
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value(0))
                    .andExpect(jsonPath("$.data").doesNotExist());
        }
    }

    @Nested
    class ErrorContract {

        @Test
        void schedulerHttpException_mapsToFastApiDetailFormat() throws Exception {
            when(schedulerFacade.assignTask(anyString(), any(), any(), any(), any(), any(), any()))
                    .thenThrow(new SchedulerHttpException(501,
                            "automatic robot allocation requires fleet state integration; "
                                    + "robot_code is required for now"));

            mockMvc.perform(post("/api/v1/scheduler/assign-task")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"taskflow_id": 1001}
                                    """))
                    .andExpect(status().is(501))
                    .andExpect(jsonPath("$.detail").value(
                            "automatic robot allocation requires fleet state integration; "
                                    + "robot_code is required for now"));
        }

        @Test
        void stopTask_notFound_returns404Detail() throws Exception {
            when(schedulerFacade.stopTask(isNull(), isNull(), isNull(), isNull()))
                    .thenThrow(new SchedulerHttpException(404, "task status record not found"));

            mockMvc.perform(post("/api/v1/scheduler/stop-task")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.detail").value("task status record not found"));
        }

        @Test
        void dispatchTask_missingTemplateType_returns400Detail() throws Exception {
            mockMvc.perform(post("/api/v1/scheduler/dispatch_task")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"task_id": "task-001"}
                                    """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail").exists());
        }

        @Test
        void unexpectedException_returns500DetailWithoutStack() throws Exception {
            when(schedulerFacade.queryTaskStatus("boom"))
                    .thenThrow(new IllegalStateException("boom detail"));

            mockMvc.perform(get("/api/v1/scheduler/tasks/{taskId}/status", "boom"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.detail").value("internal scheduler error"));
        }
    }

    @Nested
    class QueryContract {

        @Test
        void listTasks_bindsSnakeCaseQueryParams() throws Exception {
            when(schedulerFacade.listTasks("R001", true, "running", 50, false))
                    .thenReturn(Map.of("tasks", List.of(), "count", 0, "total", 0,
                            "execution_final_included", true));

            mockMvc.perform(get("/api/v1/scheduler/rmf/tasks")
                            .param("robot_code", "R001")
                            .param("execution_final", "true")
                            .param("status", "running")
                            .param("limit", "50")
                            .param("verbose", "false"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.count").value(0))
                    .andExpect(jsonPath("$.data.execution_final_included").value(true));

            verify(schedulerFacade).listTasks("R001", true, "running", 50, false);
        }

        @Test
        void listTasks_defaultQueryParams() throws Exception {
            when(schedulerFacade.listTasks(isNull(), eq(false), isNull(), eq(100), eq(true)))
                    .thenReturn(Map.of("tasks", List.of(), "count", 0, "total", 0,
                            "execution_final_included", false));

            mockMvc.perform(get("/api/v1/scheduler/rmf/tasks"))
                    .andExpect(status().isOk());

            verify(schedulerFacade).listTasks(null, false, null, 100, true);
        }

        @Test
        void taskStatus_byRequestId_bindsRequiredParam() throws Exception {
            when(schedulerFacade.queryTaskStatusByRequestId("req-001"))
                    .thenReturn(Map.of("task_id", "task-1", "task_status", "queued"));

            mockMvc.perform(get("/api/v1/scheduler/tasks/status")
                            .param("scheduler_request_id", "req-001"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.task_status").value("queued"));
        }

        @Test
        void taskStatus_byRequestId_missingParam_returns400() throws Exception {
            mockMvc.perform(get("/api/v1/scheduler/tasks/status"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail").exists());
        }

        @Test
        void preSwitchCheck_bindsParams() throws Exception {
            when(schedulerFacade.preSwitchCheck("scene-2", "fleet_A"))
                    .thenReturn(Map.of("allowed", true, "check_result", "ready"));

            mockMvc.perform(get("/api/v1/scheduler/pre-switch-check")
                            .param("scene_code", "scene-2")
                            .param("fleet_name", "fleet_A"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.allowed").value(true))
                    .andExpect(jsonPath("$.data.check_result").value("ready"));
        }

        @Test
        void runtimeSnapshot_bindsParams() throws Exception {
            when(schedulerFacade.runtimeSnapshot("scene-2", "fleet_A", "L1", 500))
                    .thenReturn(Map.of("generated_at", 1L));

            mockMvc.perform(get("/api/v1/runtime/fleets/snapshot")
                            .param("scene_code", "scene-2")
                            .param("fleet_name", "fleet_A")
                            .param("rmf_level", "L1")
                            .param("task_limit", "500"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.generated_at").value(1));

            verify(schedulerFacade).runtimeSnapshot("scene-2", "fleet_A", "L1", 500);
        }

        @Test
        void robotResources_returnsEmptyObjectData() throws Exception {
            when(schedulerFacade.robotResources("R001")).thenReturn(Map.of());

            mockMvc.perform(get("/api/v1/scheduler/robot-resources")
                            .param("robot_code", "R001"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isMap());
        }
    }

    @Nested
    class ResourceContract {

        @Test
        void assignCharger_success_returnsChargerWaypoint() throws Exception {
            when(schedulerFacade.assignCharger(eq("R001"), any(), isNull()))
                    .thenReturn(Map.of("charger_waypoint", "charger_A"));

            mockMvc.perform(post("/api/v1/scheduler/assign-charger")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"robot_code": "R001"}
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.charger_waypoint").value("charger_A"));
        }

        @Test
        void releaseCharger_missingBothTargets_returns400() throws Exception {
            when(schedulerFacade.releaseCharger(isNull(), isNull(), isNull()))
                    .thenThrow(new SchedulerHttpException(400,
                            "robot_code or charger_waypoint is required"));

            mockMvc.perform(post("/api/v1/scheduler/release-charger")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail").value("robot_code or charger_waypoint is required"));
        }

        @Test
        void sceneResourceStatus_returnsEnvelope() throws Exception {
            when(schedulerFacade.sceneResourceStatus())
                    .thenReturn(Map.of("configured", false, "status", "not_loaded"));

            mockMvc.perform(get("/api/v1/scheduler/scene-resources/status"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.configured").value(false))
                    .andExpect(jsonPath("$.data.status").value("not_loaded"));
        }

        @Test
        void reloadSceneResources_emptyBody_returnsSkippedResponse() throws Exception {
            when(schedulerFacade.reloadSceneResources(isNull(), eq(true)))
                    .thenReturn(Map.of("status", "skipped"));

            ResultActions result = mockMvc.perform(post("/api/v1/scheduler/scene-resources/reload"));
            result.andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("skipped"));

            verify(schedulerFacade).reloadSceneResources(null, true);
        }

        @Test
        void resourcesStatus_returnsEnvelopeWithLocalPath() throws Exception {
            when(schedulerFacade.resourcesStatus())
                    .thenReturn(Map.of("configured", false, "local_path", "/scene"));

            mockMvc.perform(get("/api/v1/resources/status"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.local_path").value("/scene"));
        }
    }
}
