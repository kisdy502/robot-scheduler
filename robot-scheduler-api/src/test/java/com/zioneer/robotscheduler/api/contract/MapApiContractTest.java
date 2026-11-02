package com.zioneer.robotscheduler.api.contract;

import com.zioneer.robotscheduler.api.config.ApiExceptionHandler;
import com.zioneer.robotscheduler.api.config.MapExceptionHandler;
import com.zioneer.robotscheduler.api.config.MapStorageProperties;
import com.zioneer.robotscheduler.api.controller.MapController;
import com.zioneer.robotscheduler.common.core.error.ErrorCode;
import com.zioneer.robotscheduler.core.map.MapManager;
import com.zioneer.robotscheduler.core.map.MapMetadataParser;
import com.zioneer.robotscheduler.core.repository.MapArchiveRepository;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.nio.charset.StandardCharsets;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 地图管理采用 Result，不能落入兼容调度接口的 detail 错误信封。 */
class MapApiContractTest {
    private static final String MAP = """
            {"versionInfo":{"version":"v1","status":"published"},
             "mapInfo":{"mapName":"ContractMap","floors":[{
               "floorId":"F1","defaultMaxSpeed":1.0,"waypoints":[],"paths":[]
             }]}}
            """;

    private MockMultipartFile metadata(String json) {
        return new MockMultipartFile("metadata", "metadata.json", "application/json",
                json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void uploadWhileBound_returns409Result_thenSucceedsAfterRelease() throws Exception {
        MapManager manager = new MapManager(new MapMetadataParser(), mock(MapArchiveRepository.class));
        manager.activateFromJson(MAP, null, null);
        var mvc = MockMvcBuilders.standaloneSetup(new MapController(manager, new MapStorageProperties()))
                .setControllerAdvice(new MapExceptionHandler(), new ApiExceptionHandler()).build();
        try (var binding = manager.bindTask()) {
            mvc.perform(multipart("/api/v1/maps/upload").file(metadata(MAP.replace("v1", "v2"))))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(ErrorCode.SCHEDULER_MAP_SWITCH_CONFLICT.getCode()))
                    .andExpect(jsonPath("$.msg").isNotEmpty())
                    .andExpect(jsonPath("$.data").isEmpty())
                    .andExpect(jsonPath("$.detail").doesNotExist());
            mvc.perform(get("/api/v1/maps/current"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value("v1"));
        }
        mvc.perform(multipart("/api/v1/maps/upload").file(metadata(MAP.replace("v1", "v2"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value(0))
                .andExpect(jsonPath("$.data.version").value("v2"));
    }

    @Test
    void draftMap_returns400Result() throws Exception {
        MapManager manager = new MapManager(new MapMetadataParser(), mock(MapArchiveRepository.class));
        var mvc = MockMvcBuilders.standaloneSetup(new MapController(manager, new MapStorageProperties()))
                .setControllerAdvice(new MapExceptionHandler(), new ApiExceptionHandler()).build();
        mvc.perform(multipart("/api/v1/maps/upload").file(metadata(MAP.replace("published", "draft"))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(10000))
                .andExpect(jsonPath("$.detail").doesNotExist());
    }
}
