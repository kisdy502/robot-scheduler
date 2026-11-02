package com.zioneer.robotscheduler.api.controller;

import com.zioneer.robotscheduler.common.core.result.Result;
import com.zioneer.robotscheduler.core.map.MapManager;
import com.zioneer.robotscheduler.core.map.MapMetadata;
import com.zioneer.robotscheduler.core.map.WaypointType;
import com.zioneer.robotscheduler.core.routing.NavigationGraph;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 地图管理端点（内部管理接口：Result 信封 + multipart 上传；对外调度端点不在此处）。
 *
 * <p>地图元数据一个 json 全量描述（SLAM 坐标系）；png 与 SLAM yaml 为附件（存档路径）。</p>
 */
@Tag(name = "地图管理")
@Slf4j
@RestController
@RequestMapping("/api/v1/maps")
@RequiredArgsConstructor
public class MapController {

    private final MapManager mapManager;
    private final com.zioneer.robotscheduler.api.config.MapStorageProperties storageProperties;

    @Operation(summary = "上传并激活地图（multipart: metadata.json 必填，map.png / map.yaml 可选附件）",
            description = "仅 published 版本可激活；有任务绑定或交通占用（含驻留）时返回 HTTP 409 + Result 失败信封")
    @PostMapping("/upload")
    public Result<Map<String, Object>> upload(
            @RequestPart("metadata") MultipartFile metadataFile,
            @RequestPart(value = "pgm", required = false) MultipartFile pgmFile,
            @RequestPart(value = "yaml", required = false) MultipartFile yamlFile) throws IOException {
        String metadataJson = new String(metadataFile.getBytes(), StandardCharsets.UTF_8);
        String pgmPath = storeAttachment(pgmFile);
        String yamlPath = storeAttachment(yamlFile);
        MapMetadata metadata = mapManager.activateFromJson(metadataJson, pgmPath, yamlPath);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("map_name", metadata.getMapName());
        data.put("version", metadata.getVersionInfo().getVersion());
        data.put("floors", metadata.getFloors().size());
        data.put("activated", true);
        return Result.ok(data);
    }

    @Operation(summary = "当前激活地图摘要")
    @GetMapping("/current")
    public Result<Map<String, Object>> current() {
        Map<String, Object> data = new LinkedHashMap<>();
        if (!mapManager.isLoaded()) {
            data.put("loaded", false);
            return Result.ok(data);
        }
        var snapshot = mapManager.activeSnapshot().orElseThrow();
        MapMetadata metadata = snapshot.metadata();
        NavigationGraph graph = snapshot.graph();
        data.put("loaded", true);
        data.put("map_name", metadata.getMapName());
        data.put("version", metadata.getVersionInfo().getVersion());
        data.put("coordinate_system", metadata.getCoordinateSystem());
        data.put("floors", metadata.getFloors().stream().map(floor -> Map.of(
                "floor_id", floor.getFloorId(),
                "name", floor.getName() == null ? "" : floor.getName(),
                "elevation", floor.getElevation(),
                "waypoints", floor.getWaypoints().size(),
                "paths", floor.getPaths().size(),
                "zones", floor.getZoneConstraints().size())).toList());
        data.put("inter_floor_connections", metadata.getInterFloorConnections().size());
        data.put("active_floor_id", snapshot.defaultFloorId());
        data.put("loaded_at", snapshot.loadedAt().toEpochMilli());
        data.put("graph_edges", graph.floorIds().size());
        return Result.ok(data);
    }

    @Operation(summary = "当前地图图片（PNG 格式，PGM 自动转换）")
    @GetMapping("/current/image")
    public org.springframework.http.ResponseEntity<byte[]> currentImage() {
        if (!mapManager.isLoaded()) {
            return org.springframework.http.ResponseEntity.notFound().build();
        }
        try {
            String pgmPath = getPgmPathFromStorage();
            if (pgmPath == null) {
                return org.springframework.http.ResponseEntity.notFound().build();
            }
            java.nio.file.Path pgm = java.nio.file.Path.of(pgmPath);
            if (!java.nio.file.Files.exists(pgm)) {
                return org.springframework.http.ResponseEntity.notFound().build();
            }
            // 优先找同名 PNG
            java.nio.file.Path png = pgm.resolveSibling(
                    pgm.getFileName().toString().replaceAll("\\.p[bgn]m?$", ".png"));
            if (java.nio.file.Files.exists(png)) {
                return org.springframework.http.ResponseEntity.ok()
                        .header("Content-Type", "image/png")
                        .header("Cache-Control", "max-age=30")
                        .body(java.nio.file.Files.readAllBytes(png));
            }
            // PGM 转 PNG（P5 二进制灰度图）
            byte[] pngBytes = convertPgmToPng(java.nio.file.Files.readAllBytes(pgm));
            if (pngBytes != null) {
                return org.springframework.http.ResponseEntity.ok()
                        .header("Content-Type", "image/png")
                        .header("Cache-Control", "max-age=30")
                        .body(pngBytes);
            }
            return org.springframework.http.ResponseEntity.notFound().build();
        } catch (Exception e) {
            log.warn("地图图片获取失败: {}", e.getMessage());
            return org.springframework.http.ResponseEntity.status(500).build();
        }
    }

    /** PGM(P5 二进制灰度) → PNG 字节数组；解析失败返回 null */
    private byte[] convertPgmToPng(byte[] pgm) {
        try {
            // 跳过 magic（P5）和注释，读取 width/height/maxval
            int pos = 0;
            // P5
            pos = skipWhitespaceAndComments(pgm, pos + 2);
            int width = readInt(pgm, pos);
            pos = skipWhitespaceAndComments(pgm, pos);
            int height = readInt(pgm, pos);
            pos = skipWhitespaceAndComments(pgm, pos);
            int maxval = readInt(pgm, pos);
            pos++; // 单个空白分隔符
            if (width <= 0 || height <= 0 || maxval <= 0 || maxval > 255) return null;

            java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(
                    width, height, java.awt.image.BufferedImage.TYPE_BYTE_GRAY);
            img.getRaster().setDataElements(0, 0, width, height,
                    java.util.Arrays.copyOfRange(pgm, pos, pos + width * height));
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (Exception e) {
            log.warn("PGM→PNG 转换失败: {}", e.getMessage());
            return null;
        }
    }

    private int skipWhitespaceAndComments(byte[] data, int pos) {
        while (pos < data.length) {
            if (data[pos] == '#') {
                while (pos < data.length && data[pos] != '\n') pos++;
            } else if (data[pos] == ' ' || data[pos] == '\t' || data[pos] == '\r' || data[pos] == '\n') {
                pos++;
            } else {
                break;
            }
        }
        return pos;
    }

    private int readInt(byte[] data, int pos) {
        int val = 0;
        while (pos < data.length && data[pos] >= '0' && data[pos] <= '9') {
            val = val * 10 + (data[pos] - '0');
            pos++;
        }
        return val;
    }

    @Operation(summary = "当前地图地理信息（origin/resolution/尺寸，前端定位地图图片用）")
    @GetMapping("/current/geo")
    public Result<Map<String, Object>> currentGeo() {
        if (!mapManager.isLoaded()) {
            return Result.ok(Map.of("loaded", false));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("loaded", true);
        try {
            String yamlPath = getYamlPathFromStorage();
            if (yamlPath != null && java.nio.file.Files.exists(java.nio.file.Path.of(yamlPath))) {
                // 简易 YAML 解析（只需几个标量字段）
                java.util.Properties props = new java.util.Properties();
                try (var reader = new java.io.StringReader(
                        java.nio.file.Files.readString(java.nio.file.Path.of(yamlPath))
                                .replaceAll(":", "=")
                                .replaceAll("#.*\\n", "\n"))) {
                    props.load(reader);
                }
                data.put("resolution", Double.parseDouble(props.getProperty("resolution", "0.05").trim()));
                data.put("width", Integer.parseInt(props.getProperty("width", "0").trim()));
                data.put("height", Integer.parseInt(props.getProperty("height", "0").trim()));
                // origin: [-16.4, -18.75, 0.0]
                String originStr = props.getProperty("origin", "[0, 0, 0]").replaceAll("[\\[\\]]", "").trim();
                String[] parts = originStr.split(",");
                data.put("origin_x", Double.parseDouble(parts[0].trim()));
                data.put("origin_y", Double.parseDouble(parts[1].trim()));
            }
        } catch (Exception e) {
            log.warn("地图 geo 信息解析失败: {}", e.getMessage());
        }
        return Result.ok(data);
    }

    private String getPgmPathFromStorage() {
        String activePath = mapManager.activeSnapshot().map(snapshot -> snapshot.pgmPath()).orElse(null);
        if (activePath != null && !activePath.isBlank()) return activePath;
        // 2. 回退：本地配置目录找 .pgm / .png
        try {
            String metadataPath = storageProperties.getMetadataPath();
            if (metadataPath != null && !metadataPath.isBlank()) {
                java.nio.file.Path dir = java.nio.file.Path.of(metadataPath).getParent();
                if (dir != null && java.nio.file.Files.exists(dir)) {
                    // 找 .png 优先
                    try (var files = java.nio.file.Files.list(dir)) {
                        var png = files.filter(f -> f.getFileName().toString().toLowerCase().endsWith(".png"))
                                .filter(f -> f.getFileName().toString().contains("global_map"))
                                .findFirst();
                        if (png.isPresent()) return png.get().toString();
                    }
                    try (var files = java.nio.file.Files.list(dir)) {
                        var pgm = files.filter(f -> {
                            String n = f.getFileName().toString().toLowerCase();
                            return n.endsWith(".pgm") || n.endsWith(".pbm") || n.endsWith(".pnm");
                        }).findFirst();
                        if (pgm.isPresent()) return pgm.get().toString();
                    }
                }
            }
        } catch (Exception e) {
            log.warn("本地地图文件查找失败: {}", e.getMessage());
        }
        // 3. 回退：配置的 pgmPath
        String pgm = storageProperties.getPgmPath();
        if (pgm != null && !pgm.isBlank()) return pgm;
        return null;
    }

    private String getYamlPathFromStorage() {
        String activePath = mapManager.activeSnapshot().map(snapshot -> snapshot.yamlPath()).orElse(null);
        if (activePath != null && !activePath.isBlank()) return activePath;
        // 2. 本地目录找 .yaml
        try {
            String metadataPath = storageProperties.getMetadataPath();
            if (metadataPath != null && !metadataPath.isBlank()) {
                java.nio.file.Path dir = java.nio.file.Path.of(metadataPath).getParent();
                if (dir != null && java.nio.file.Files.exists(dir)) {
                    try (var files = java.nio.file.Files.list(dir)) {
                        var yaml = files.filter(f -> {
                            String n = f.getFileName().toString().toLowerCase();
                            return n.endsWith(".yaml") || n.endsWith(".yml");
                        }).filter(f -> !f.getFileName().toString().endsWith(".lxmap"))
                        .findFirst();
                        if (yaml.isPresent()) return yaml.get().toString();
                    }
                }
            }
        } catch (Exception e) { /* */ }
        // 3. 配置
        String yaml = storageProperties.getYamlPath();
        if (yaml != null && !yaml.isBlank()) return yaml;
        return null;
    }

    @Operation(summary = "当前地图完整详情（含路径/区域，前端地图渲染数据源）")
    @GetMapping("/current/detail")
    public Result<Map<String, Object>> currentDetail() {
        if (!mapManager.isLoaded()) {
            return Result.ok(Map.of("loaded", false));
        }
        MapMetadata metadata = mapManager.activeMetadata().orElseThrow();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("loaded", true);
        data.put("map_name", metadata.getMapName());
        data.put("version", metadata.getVersionInfo().getVersion());
        data.put("floors", metadata.getFloors().stream().map(floor -> {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("floor_id", floor.getFloorId());
            f.put("name", floor.getName() == null ? "" : floor.getName());
            f.put("waypoints", floor.getWaypoints().stream().map(wp -> {
                Map<String, Object> w = new LinkedHashMap<>();
                w.put("id", wp.getId());
                w.put("name", wp.getName());
                w.put("type", wp.getType().wireName());
                w.put("x", wp.getX());
                w.put("y", wp.getY());
                w.put("theta", wp.getTheta());
                return w;
            }).toList());
            f.put("paths", floor.getPaths().stream().map(p -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", p.getId());
                item.put("from", p.getFrom());
                item.put("to", p.getTo());
                item.put("waypoints", p.getWaypoints());
                item.put("bidirectional", p.isBidirectional());
                item.put("max_speed", p.getMaxSpeed());
                item.put("segments", p.getSegments().stream().map(seg -> {
                    Map<String, Object> s = new LinkedHashMap<>();
                    s.put("seq", seg.getSeq());
                    s.put("from", seg.getFrom());
                    s.put("to", seg.getTo());
                    s.put("segment_type", seg.getSegmentType());
                    s.put("control_points", seg.getControlPoints());
                    s.put("back_up", seg.isBackUp());
                    s.put("reverse", seg.isReverse());
                    return s;
                }).toList());
                return item;
            }).toList());
            f.put("zones", floor.getZoneConstraints().stream().map(z -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", z.getId());
                item.put("name", z.getName());
                item.put("type", z.getType());
                item.put("max_speed", z.getMaxSpeed());
                item.put("vertices", z.getVertices());
                return item;
            }).toList());
            return f;
        }).toList());
        return Result.ok(data);
    }

    @Operation(summary = "当前地图点位查询（联调/调试用，支持按楼层与类型过滤）")
    @GetMapping("/current/waypoints")
    public Result<List<Map<String, Object>>> waypoints(
            @Parameter(description = "楼层 ID，默认当前激活楼层")
            @RequestParam(name = "floor_id", required = false) String floorId,
            @Parameter(description = "点位类型过滤，如 charge/parking/workstation")
            @RequestParam(name = "type", required = false) String type) {
        if (!mapManager.isLoaded()) {
            return Result.ok(List.of());
        }
        NavigationGraph graph = mapManager.activeGraph().orElseThrow();
        String targetFloor = floorId != null && !floorId.isBlank() ? floorId
                : graph.source().getFloors().get(0).getFloorId();
        List<com.zioneer.robotscheduler.core.map.WaypointMetadata> waypoints = type == null || type.isBlank()
                ? allWaypoints(graph, targetFloor)
                : graph.waypointsByType(targetFloor, WaypointType.fromWireName(type));
        return Result.ok(waypoints.stream().map(waypoint -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", waypoint.getId());
            row.put("name", waypoint.getName());
            row.put("floor_id", targetFloor);
            row.put("x", waypoint.getX());
            row.put("y", waypoint.getY());
            row.put("theta", waypoint.getTheta());
            row.put("type", waypoint.getType().wireName());
            return row;
        }).toList());
    }

    private List<com.zioneer.robotscheduler.core.map.WaypointMetadata> allWaypoints(NavigationGraph graph,
                                                                                    String floorId) {
        return graph.source().getFloors().stream()
                .filter(floor -> floorId == null || floor.getFloorId().equals(floorId))
                .flatMap(floor -> floor.getWaypoints().stream())
                .toList();
    }

    private String storeAttachment(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            return null;
        }
        Path dir = Path.of(storageProperties.getAttachmentDir());
        Files.createDirectories(dir);
        Path target = dir.resolve(System.currentTimeMillis() + "_" + file.getOriginalFilename());
        Files.write(target, file.getBytes());
        return target.toString();
    }
}
