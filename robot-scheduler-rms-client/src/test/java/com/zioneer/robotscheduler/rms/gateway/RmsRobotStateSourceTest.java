package com.zioneer.robotscheduler.rms.gateway;

import com.zioneer.robotscheduler.rms.client.RobotManagementClient;
import com.zioneer.robotscheduler.rms.client.model.RmsRobotStatus;
import com.zioneer.robotscheduler.rms.config.RobotManagementProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RmsRobotStateSourceTest {

    // RMS 缺少坐标时必须标记无效，不能把默认原点作为实际到点证据；真实原点仍有效。
    @Test
    void fetchStates_missingCoordinates_areInvalidButRealOriginIsValid() {
        var client = mock(RobotManagementClient.class);
        var missing = new RmsRobotStatus();
        missing.setRobotCode("missing");
        var origin = new RmsRobotStatus();
        origin.setRobotCode("origin");
        origin.setX(0.0);
        origin.setY(0.0);
        var partial = new RmsRobotStatus();
        partial.setRobotCode("partial");
        partial.setX(1.0);
        when(client.fetchStatus(any(), any())).thenReturn(List.of(missing, origin, partial));
        var states = new RmsRobotStateSource(client, new RobotManagementProperties()).fetchStates();
        assertThat(states).extracting(state -> state.isPositionValid()).containsExactly(false, true, false);
        assertThat(states.get(1).getX()).isZero();
        assertThat(states.get(1).getY()).isZero();
    }
}
