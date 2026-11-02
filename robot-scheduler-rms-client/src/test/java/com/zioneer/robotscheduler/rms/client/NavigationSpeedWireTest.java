package com.zioneer.robotscheduler.rms.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.zioneer.robotscheduler.rms.config.RobotManagementProperties;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** 本地 HTTP 接收端验证真实序列化请求，不连接外部 RMS。 */
class NavigationSpeedWireTest {

    @Test
    void navigate_serializesSpeedLimitWithoutChangingCoordinates() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AtomicReference<JsonNode> request = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/robot-management/rmf/robots/R001/navigate", exchange -> {
            try {
                request.set(mapper.readTree(exchange.getRequestBody()));
                byte[] response = "{\"status\":\"success\",\"data\":{\"accepted\":true}}"
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            RobotManagementProperties properties = new RobotManagementProperties();
            properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            HttpRobotManagementClient client = new HttpRobotManagementClient(properties, RestClient.builder(), mapper);
            assertThat(client.navigate("R001", 42, "F1", 6, 2, 0.5, 0.3, Map.of()).isAccepted()).isTrue();
            assertThat(request.get().path("speed_limit").asDouble()).isEqualTo(0.3);
            assertThat(request.get().path("cmd_id").asText()).isEqualTo("42");
            assertThat(request.get().path("destination").path("x").asDouble()).isEqualTo(6);
            assertThat(request.get().path("destination").path("y").asDouble()).isEqualTo(2);
            assertThat(request.get().path("destination").path("yaw").asDouble()).isEqualTo(0.5);
        } finally {
            server.stop(0);
        }
    }
}
