# AGENTS.md — robot-scheduler

## 服务职责

机器人调度服务（Java 重构版）：替代原 `robot-scheduler-rest-api + rmf-qc-fleet-adapter + RMF Core` 三件套。
对外提供与旧 REST API（0.2.0）字段一致的 17 个调度端点；对内实现简化调度内核（拓扑图/路径规划/资源占用/任务分配/任务执行）；南向通过 HTTP 调用 RMS 管理机器人。

**不负责**：任务流编排（taskflow-management-service）、机器人本体导航（RMS/机器人）、质检业务（quality-inspection-service）。

## 设计原则（重要）

1. **自主设计，不照搬 RMF**：可借鉴 RMF 的调度思想（资源占用/集中分配），但数据模型、模块划分、坐标系、配置体系按本项目自己的设计。
2. **SLAM 坐标系零转换**：地图 json、机器人上报坐标、目标点、区域多边形全部使用 SLAM map 坐标系（米制）；禁止引入任何坐标变换层。
3. **单 json 地图元数据**：一个地图 = 一份元数据 json（详见《地图编辑json文件格式.md》）+ png + SLAM yaml 附件；旧栈 scene bundle（points/chargers/homes/...十几个配置）已废弃。
4. **调度只发目标点**：路径规划仅用于 ETA 估算与交通占用管理；机器人导航由 RMS/机器人自主完成，速度/禁区约束随目标点参数下发。

## 模块结构

| 模块 | 职责 | 依赖方向 |
|---|---|---|
| `robot-scheduler-common` | 基础协议（Result/ErrorCode/分页）+ 调度领域字典（常量/枚举），零 Spring 依赖 | 被所有模块依赖 |
| `robot-scheduler-core` | 调度内核：**map**（地图元数据/解析/MapManager）+ **routing**（NavigationGraph/规划/几何）+ traffic（资源占用）+ task/fleet/charger/dispatch + 全部端口接口定义 | → common（允许 jackson-databind 用于地图解析） |
| `robot-scheduler-rms-client` | RMS HTTP 客户端（8 端点）+ Mock，实现 core 的 RobotGateway 端口 | → core |
| `robot-scheduler-persistence` | MyBatis-Plus + Flyway 实现 core 的 Repository 端口（含 t_map_metadata 地图存档） | → core |
| `robot-scheduler-api` | REST 兼容层（17 对外端点 + 地图管理内部端点）+ 全局异常 + OpenAPI | → core |
| `robot-scheduler-boot` | 启动装配（含地图启动加载 Runner） | → 全部 |

**依赖倒置约定**：core 只定义端口（RobotGateway/TaskRecordRepository/ChargerPoolPort/MapArchiveRepository），实现放 rms-client / persistence；core 禁止依赖 spring-web / mybatis（jackson-databind 为地图解析豁免项）。

## 对外字段映射（兼容保留，内部语义已切换）

| 旧字段名（对外不变） | 新内部语义 |
|---|---|
| `rmf_task_id` | 调度内核任务 ID |
| `rmf_waypoint` | 地图 waypoint id（如 WP_001） |
| `rmf_level` | 地图 floorId（如 F1） |
| `scene_code` | mapName |
| `bundle_version` | 地图 versionInfo.version |
| `nav_target_point` | waypoint id 或 waypoint name |
| 充电位 `charger_waypoint` | type=charge 的 waypoint id |

## 规则挂载

本地规则 > `D:\workspace\qc-robot\team-ai-rules`（00-global / 10-java / 20-springboot / 40-testing 必读）。

## Local Overrides（本服务覆盖规则）

1. **对外契约优先**：`/api/v1/**` 的响应是旧系统契约——统一信封 `{status, msg, data, timestamp}`（非 `Result`），错误为 FastAPI 风格 `{"detail": "..."}` + HTTP 状态码（400/404/409/501/503/504）。msg 固定文案 `request published; execution pending` 不可改。
2. **字段名**：`rmf_task_id`、`dispatch_status: published|rmf_response_received|cancel_requested`、`rmf_response_received` 等 rmf_ 前缀字段名是为兼容保留，禁止改名。
3. 内部管理接口（未来新增 admin/**）使用 `Result<T>` + `@RequirePermission`。
4. 分层：Controller 只做参数绑定与信封包装，业务在 core；core 的 facade（SchedulerFacade）是对 api 层唯一入口。地图管理端点（/api/v1/maps/**）为内部管理接口，使用 Result 信封。
5. 数据库：只允许 MyBatis-Plus API（BaseMapper + LambdaQueryWrapper/LambdaUpdateWrapper），**禁止 mapper XML 与手写业务 SQL**；DDL 只出现在 Flyway 迁移脚本。
6. 时间契约：对外 JSON 时间一律 unix 毫秒（long）；DB 内 TIMESTAMPTZ/Instant。
7. 任务状态语义（不可变更）：终态 {completed, failed, canceled}；活动任务取消 = 发布 completed + cancellation 标记；排队任务取消 = canceled（fork 语义）。
8. 常量集中在 `SchedulerConstants` / `TaskCategories` / `TaskStatus`，值需与旧栈 constants.py 对齐（附录 A of 重构方案）。
9. 地图契约：字段结构以《地图编辑json文件格式.md》为准；position=[x,y,theta] 数组、点位类型小写 wireName、仅 published 版本可激活；楼层维度向后兼容（跨层调度 P4+）。

## Delivery Rules

- 改接口 → 同步 DTO/VO/Swagger/契约测试（对照旧 `test_rest_api.py` 断言）
- 改表 → Flyway 新增迁移脚本 + Entity/Mapper 同步 + 兼容性评估
- 改 RMS 交互 → 错误处理（三态映射）+ 日志（带 robot_code/cmd_id）+ Mock 同步
- 验证：`mvn -q -DskipTests compile` 通过后交付；核心逻辑补单测（Mockito，不拉 Spring 上下文）

## 提交规范

`type(scope): description`，scope ∈ {common, core, rms, persistence, api, boot}。例：`feat(core): add lane-level traffic acquisition`。
