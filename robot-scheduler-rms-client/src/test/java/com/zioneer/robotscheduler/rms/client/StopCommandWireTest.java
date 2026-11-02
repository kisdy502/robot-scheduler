package com.zioneer.robotscheduler.rms.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.zioneer.robotscheduler.rms.config.RobotManagementProperties;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.assertThat;

class StopCommandWireTest {
    @Test
    void noActiveTaskIsIdempotent_otherRejectionsRemainFailures() throws Exception {
        AtomicReference<String> code = new AtomicReference<>("NO_ACTIVE_TASK");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/robot-management/rmf/robots/R001/stop", exchange -> {
            try {
                exchange.getRequestBody().readAllBytes();
                byte[] response = ("{\"status\":\"success\",\"data\":{\"accepted\":false,"
                        + "\"retriable\":false,\"result_code\":\"" + code.get() + "\"}}")
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
            var client = new HttpRobotManagementClient(properties, RestClient.builder(), new ObjectMapper());
            assertThat(client.stop("R001", 0, 1, null).isAccepted()).isTrue();
            code.set("ROBOT_STOP_REJECTED");
            assertThat(client.stop("R001", 0, 2, null).isImpossible()).isTrue();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void mockSupportsNoActiveTask_andNormalStopRejection() {
        var client = new MockRobotManagementClient();
        client.injectNoActiveTaskOnStop("R001");
        assertThat(client.stop("R001", 0, 1, null).getData()).containsEntry("no_active_task", true);
        client.rejectNextCommands(1);
        assertThat(client.stop("R001", 0, 2, null).isAccepted()).isFalse();
    }
}
