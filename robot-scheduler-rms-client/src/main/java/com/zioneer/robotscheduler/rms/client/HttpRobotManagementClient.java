package com.zioneer.robotscheduler.rms.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zioneer.robotscheduler.rms.client.model.RmsCommandResult;
import com.zioneer.robotscheduler.rms.client.model.RmsInventoryRobot;
import com.zioneer.robotscheduler.rms.client.model.RmsRobotStatus;
import com.zioneer.robotscheduler.rms.config.RobotManagementProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * RMS HTTP 客户端默认实现（Spring RestClient）。
 *
 * <p>行为对齐原 fleet adapter 的 RobotAPI：
 * 网络错误 → RETRYABLE；命令接口按 data.accepted/retriable/result_code 归一三态；
 * 响应信封双兼容 {status,msg,data} 与 {success,data}。</p>
 */
@Slf4j
public class HttpRobotManagementClient implements RobotManagementClient {

    private static final String BASE_PATH = "/api/v1/robot-management/rmf";

    private final RobotManagementProperties properties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public HttpRobotManagementClient(RobotManagementProperties properties, RestClient.Builder builder,
                                     ObjectMapper objectMapper,
                                     org.springframework.http.client.ClientHttpRequestFactory requestFactory) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        // 超时必须生效：无超时的请求 hang 住会冻结 1Hz 状态轮询（位置停滞数分钟）
        this.restClient = builder
                .baseUrl(properties.getBaseUrl())
                .requestFactory(requestFactory)
                .build();
    }

    /** 兼容旧构造（无超时工厂，仅测试用）。 */
    HttpRobotManagementClient(RobotManagementProperties properties, RestClient.Builder builder,
                              ObjectMapper objectMapper) {
        this(properties, builder, objectMapper, defaultRequestFactory(properties));
    }

    /** 默认超时工厂：connect/read 按 properties 配置。 */
    public static org.springframework.http.client.ClientHttpRequestFactory defaultRequestFactory(
            RobotManagementProperties properties) {
        org.springframework.http.client.SimpleClientHttpRequestFactory factory =
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) (properties.getConnectTimeoutSeconds() * 1000));
        factory.setReadTimeout((int) (properties.getReadTimeoutSeconds() * 1000));
        return factory;
    }

    @Override
    public List<RmsInventoryRobot> fetchInventory(String fleetName) {
        try {
            JsonNode data = getData(restClient.get()
                    .uri(BASE_PATH + "/fleets/{fleet}/inventory", fleetName)
                    .retrieve()
                    .body(JsonNode.class));
            // 报文追踪不打 inventory：纯静态状态（无 completed_commands 回执），噪音无价值
            return toList(data, RmsInventoryRobot.class);
        } catch (ResourceAccessException | RestClientResponseException e) {
            log.warn("RMS inventory request failed, fleet={}, error={}", fleetName, e.getMessage());
            throw new RmsUnavailableException("RMS inventory request failed: " + e.getMessage(), e);
        }
    }

    @Override
    public List<RmsRobotStatus> fetchStatus(String fleetName, String robotName) {
        try {
            JsonNode data = getData(restClient.get()
                    .uri(uriBuilder -> {
                        uriBuilder.path(BASE_PATH + "/fleets/{fleet}/status");
                        if (robotName != null && !robotName.isBlank()) {
                            uriBuilder.queryParam("robot_name", robotName);
                        }
                        return uriBuilder.build(fleetName);
                    })
                    .retrieve()
                    .body(JsonNode.class));
            // 报文追踪只打带回执的帧（completed_commands 非空 = 命令完成/任务结果）；
            // 纯状态帧（位置/电量心跳）静默，避免 1Hz 噪音淹没关键消息
            if (hasCompletedCommands(data)) {
                traceIn("GET /fleets/" + fleetName + "/status 数据帧(带回执)", data);
            }
            return toList(data, RmsRobotStatus.class);
        } catch (ResourceAccessException | RestClientResponseException e) {
            log.warn("RMS status request failed, fleet={}, robot={}, error={}",
                    fleetName, robotName, e.getMessage());
            throw new RmsUnavailableException("RMS status request failed: " + e.getMessage(), e);
        }
    }

    @Override
    public java.util.Map<String, String> fetchRobotNameMap() {
        java.util.Map<String, String> map = new java.util.HashMap<>();
        try {
            JsonNode root = restClient.get()
                    .uri(BASE_PATH.replace("/rmf", "") + "/robots?page_num=1&page_size=200")
                    .retrieve()
                    .body(JsonNode.class);
            JsonNode list = root != null && root.has("data") && root.get("data").has("list")
                    ? root.get("data").get("list") : null;
            if (list != null && list.isArray()) {
                for (JsonNode item : list) {
                    String code = item.path("robot_code").asText(null);
                    String name = item.path("robot_name").asText(null);
                    if (code != null && !code.isBlank() && name != null && !name.isBlank()) {
                        map.put(code, name);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("RMS robot name map fetch failed: {}", e.getMessage());
        }
        return map;
    }

    @Override
    public java.util.Map<String, String> fetchRobotMapCodeMap() {
        java.util.Map<String, String> map = new java.util.HashMap<>();
        try {
            JsonNode root = restClient.get()
                    .uri(BASE_PATH.replace("/rmf", "") + "/robots?page_num=1&page_size=200")
                    .retrieve()
                    .body(JsonNode.class);
            JsonNode list = root != null && root.has("data") && root.get("data").has("list")
                    ? root.get("data").get("list") : null;
            if (list != null && list.isArray()) {
                for (JsonNode item : list) {
                    String code = item.path("robot_code").asText(null);
                    String mapCode = item.path("current_map_code").asText(null);
                    if (mapCode == null || mapCode.isBlank()) {
                        mapCode = item.path("current_map_name").asText(null);
                    }
                    if (code != null && !code.isBlank() && mapCode != null && !mapCode.isBlank()) {
                        map.put(code, mapCode);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("RMS robot map code map fetch failed: {}", e.getMessage());
        }
        return map;
    }

    @Override
    public java.util.Map<String, RobotProfile> fetchRobotProfiles() {
        java.util.Map<String, RobotProfile> map = new java.util.HashMap<>();
        try {
            JsonNode root = restClient.get()
                    .uri(BASE_PATH.replace("/rmf", "") + "/robots?page_num=1&page_size=200")
                    .retrieve()
                    .body(JsonNode.class);
            JsonNode list = root != null && root.has("data") && root.get("data").has("list")
                    ? root.get("data").get("list") : null;
            if (list != null && list.isArray()) {
                for (JsonNode item : list) {
                    String code = item.path("robot_code").asText(null);
                    if (code == null || code.isBlank()) {
                        continue;
                    }
                    String mapCode = item.path("current_map_code").asText(null);
                    if (mapCode == null || mapCode.isBlank()) {
                        mapCode = item.path("current_map_name").asText(null);
                    }
                    // 本体充电标记：robot_status.battery[0].is_charging（离线机器人为空结构）
                    Boolean charging = null;
                    JsonNode battery = item.path("robot_status").path("battery");
                    if (battery.isArray() && battery.size() > 0) {
                        JsonNode first = battery.get(0);
                        if (first.has("is_charging")) {
                            charging = first.get("is_charging").asBoolean(false);
                        }
                    }
                    map.put(code, new RobotProfile(mapCode, charging));
                }
            }
        } catch (Exception e) {
            log.warn("RMS robot profiles fetch failed: {}", e.getMessage());
        }
        return map;
    }

    @Override
    public RmsCommandResult navigate(String robotCode, int cmdId, String mapName,
                                     double x, double y, double yaw,
                                     Double speedLimit, Map<String, Object> description) {
        Map<String, Object> body = new HashMap<>();
        body.put("cmd_id", String.valueOf(cmdId));
        body.put("map_name", mapName);
        Map<String, Object> destination = new HashMap<>();
        destination.put("x", x);
        destination.put("y", y);
        destination.put("yaw", yaw);
        body.put("destination", destination);
        if (speedLimit != null) {
            body.put("speed_limit", speedLimit);
        }
        body.put("description", description);
        return postCommand(robotCode, "navigate", body, true);
    }

    @Override
    public RmsCommandResult startActivity(String robotCode, int cmdId, String activity, String label,
                                          Map<String, Object> description) {
        Map<String, Object> body = new HashMap<>();
        body.put("cmd_id", String.valueOf(cmdId));
        body.put("activity", wireChargeActivity(activity, description, properties.getChargeActivityName()));
        body.put("label", label);
        body.put("description", description);
        // 旧栈语义：start-activity 默认不可重试（retriable=false）
        return postCommand(robotCode, "start-activity", body, false);
    }

    /**
     * 充电动作线上模板标识：机器人侧按模板 code（服务器创建模板时生成的唯一号，如
     * auto-goto-charge101）查模板，不是按固定类型 charge——优先取任务流传入的
     * task_template_code，无 code 时用配置兜底；非充电动作原样透传。
     * 注意：description.task_template_type 是固定类型语义（恒 charge），不做改写。
     */
    static String wireChargeActivity(String activity, Map<String, Object> description,
                                     String fallbackChargeActivity) {
        if (!"charge".equals(activity)) {
            return activity;
        }
        Object code = description == null ? null : description.get("task_template_code");
        if (code instanceof String templateCode && !templateCode.isBlank()) {
            return templateCode;
        }
        return fallbackChargeActivity == null || fallbackChargeActivity.isBlank()
                ? activity : fallbackChargeActivity;
    }

    @Override
    public RmsCommandResult stop(String robotCode, int runningCmdId, int stopCmdId, String bodyTaskId) {
        Map<String, Object> body = new HashMap<>();
        body.put("running_cmd_id", String.valueOf(runningCmdId));
        body.put("stop_cmd_id", String.valueOf(stopCmdId));
        if (bodyTaskId != null && !bodyTaskId.isBlank()) {
            body.put("body_task_id", bodyTaskId);
        }
        return postCommand(robotCode, "stop", body, true);
    }

    @Override
    public RmsCommandResult pauseTask(String robotCode, int cmdId, Map<String, Object> params) {
        Map<String, Object> body = new HashMap<>(params == null ? Map.of() : params);
        body.put("cmd_id", String.valueOf(cmdId));
        return postCommand(robotCode, "pause-task", body, true);
    }

    @Override
    public RmsCommandResult resumeTask(String robotCode, int cmdId, Map<String, Object> params) {
        Map<String, Object> body = new HashMap<>(params == null ? Map.of() : params);
        body.put("cmd_id", String.valueOf(cmdId));
        return postCommand(robotCode, "resume-task", body, true);
    }

    @Override
    public RmsCommandResult ackCompletion(String robotCode, String completionId) {
        long startNanos = System.nanoTime();
        try {
            restClient.post()
                    .uri(BASE_PATH + "/robots/{robotCode}/completions/{completionId}/ack", robotCode, completionId)
                    .retrieve()
                    .toBodilessEntity();
            log.info("RMS 请求: POST /robots/{}/completions/{}/ack -> 2xx cost={}ms", robotCode, completionId,
                    (System.nanoTime() - startNanos) / 1_000_000);
            return RmsCommandResult.accepted(Map.of());
        } catch (ResourceAccessException e) {
            log.warn("RMS 请求: POST /robots/{}/completions/{}/ack -> 网络错误 {} cost={}ms", robotCode,
                    completionId, e.getMessage(), (System.nanoTime() - startNanos) / 1_000_000);
            return RmsCommandResult.retryable("ack network error: " + e.getMessage());
        } catch (RestClientResponseException e) {
            log.warn("RMS 请求: POST /robots/{}/completions/{}/ack -> HTTP {} cost={}ms", robotCode,
                    completionId, e.getStatusCode().value(), (System.nanoTime() - startNanos) / 1_000_000);
            return RmsCommandResult.impossible("ack rejected: HTTP " + e.getStatusCode().value());
        }
    }

    private RmsCommandResult postCommand(String robotCode, String action, Map<String, Object> body,
                                         boolean defaultRetriable) {
        long startNanos = System.nanoTime();
        traceOut("POST /robots/" + robotCode + "/" + action, body);
        try {
            JsonNode root = restClient.post()
                    .uri(BASE_PATH + "/robots/{robotCode}/{action}", robotCode, action)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
            traceIn("POST /robots/" + robotCode + "/" + action + " 响应", root);
            JsonNode data = extractData(root);
            // stop 的明确无活动任务响应是幂等成功；其他拒绝仍保留原有三态。
            boolean noActiveTask = "stop".equals(action) && data != null
                    && "NO_ACTIVE_TASK".equals(data.path("result_code").asText());
            RmsCommandResult result = noActiveTask
                    ? RmsCommandResult.accepted(Map.of("no_active_task", true))
                    : toCommandResult(data, defaultRetriable);
            // 命令级访问日志（navigate/start-activity/stop/pause/resume/ack 低频，全量 INFO；
            // 定时轮询的 inventory/status 不在此出口，不打）
            log.info("RMS 请求: POST /robots/{}/{} cmd_id={} -> {}{} cost={}ms", robotCode, action,
                    body.get("cmd_id"), result.isAccepted() ? "accepted" : "rejected",
                    result.isAccepted() ? "" : " detail=" + result.getMessage(),
                    (System.nanoTime() - startNanos) / 1_000_000);
            return result;
        } catch (ResourceAccessException e) {
            // 网络/超时错误统一按可重试处理（对齐旧栈）
            log.warn("RMS 请求: POST /robots/{}/{} cmd_id={} -> 网络错误 {} cost={}ms", robotCode, action,
                    body.get("cmd_id"), e.getMessage(), (System.nanoTime() - startNanos) / 1_000_000);
            return RmsCommandResult.retryable(action + " network error: " + e.getMessage());
        } catch (RestClientResponseException e) {
            log.warn("RMS 请求: POST /robots/{}/{} cmd_id={} -> HTTP {} body={} cost={}ms", robotCode, action,
                    body.get("cmd_id"), e.getStatusCode().value(), e.getResponseBodyAsString(),
                    (System.nanoTime() - startNanos) / 1_000_000);
            return RmsCommandResult.impossible(action + " rejected: HTTP " + e.getStatusCode().value()
                    + " body=" + e.getResponseBodyAsString());
        }
    }

    private JsonNode getData(JsonNode root) {
        JsonNode data = extractData(root);
        return data == null ? objectMapper.createObjectNode() : data;
    }

    /** 报文级追踪（调试联调对拍，trace-payload 开关；JSON 紧凑单行，不破坏日志换行）。
     *  级别区分方向：出站 >>> INFO（绿），入站 <<< WARN（黄），控制台一眼分清收发。 */
    private void traceOut(String what, Object payload) {
        if (properties.isTracePayload()) {
            log.info("RMS 报文 >>> {}: {}", what, toJsonText(payload));
        }
    }

    private void traceIn(String what, Object payload) {
        if (properties.isTracePayload()) {
            log.warn("RMS 报文 <<< {}: {}", what, toJsonText(payload));
        }
    }

    private String toJsonText(Object payload) {
        if (payload == null) {
            return "null";
        }
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            return String.valueOf(payload);
        }
    }

    /** 状态帧是否携带命令完成回执（任一机器人 completed_commands 非空）。 */
    private boolean hasCompletedCommands(JsonNode data) {
        if (data == null || !data.has("robots")) {
            return false;
        }
        for (JsonNode robot : data.get("robots")) {
            JsonNode receipts = robot.path("completed_commands");
            if (receipts.isArray() && receipts.size() > 0) {
                return true;
            }
        }
        return false;
    }

    /** 信封双兼容：优先 data 字段（{status,msg,data} / {success,data}）。 */
    private JsonNode extractData(JsonNode root) {
        if (root == null) {
            return null;
        }
        if (root.has("data") && root.get("data").isObject()) {
            return root.get("data");
        }
        if (root.has("robots")) {
            return root.get("robots");
        }
        return root;
    }

    private RmsCommandResult toCommandResult(JsonNode data, boolean defaultRetriable) {
        if (data == null) {
            return RmsCommandResult.retryable("empty command response");
        }
        boolean accepted = data.path("accepted").asBoolean(false);
        boolean retriable = data.has("retriable") ? data.path("retriable").asBoolean(defaultRetriable)
                : defaultRetriable;
        if (accepted) {
            Map<String, Object> extra = objectMapper.convertValue(
                    data.path("data"), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                    });
            return RmsCommandResult.accepted(extra);
        }
        String message = data.path("message").asText(data.path("reject_reason").asText("command not accepted"));
        return retriable ? RmsCommandResult.retryable(message) : RmsCommandResult.impossible(message);
    }

    private <T> List<T> toList(JsonNode data, Class<T> type) {
        List<T> result = new ArrayList<>();
        if (data != null && data.isArray()) {
            for (JsonNode item : data) {
                // 双兼容：条目字段 snake_case 优先（RMS 契约），camelCase 由 @JsonAlias 兜底
                result.add(objectMapper.convertValue(item, type));
            }
        } else if (data != null && data.isObject() && data.has("robots") && data.get("robots").isArray()) {
            for (JsonNode item : data.get("robots")) {
                result.add(objectMapper.convertValue(item, type));
            }
        }
        return result;
    }

    Duration readTimeout() {
        return Duration.ofMillis((long) (properties.getReadTimeoutSeconds() * 1000));
    }
}
