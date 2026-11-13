# 存储设计说明

> 调度系统存储分三层：**PostgreSQL（持久正本）** + **进程内存（运行态）** + **Redis（已配置连接、当前未使用，多实例演进预留）**。

## 一、PostgreSQL 表（Flyway 管理）

数据库：`scheduler_service`（调度 PG 实例 55432）

### 1.1 t_task_record —— 调度请求记录（受理台账）

**存什么**：每次 API 下发任务的受理记录（含自动返充等内部任务），按 `scheduler_request_id` 对外查询。

| 字段 | 类型 | 说明 |
|---|---|---|
| id | BIGINT PK | 雪花 ID |
| scheduler_request_id | VARCHAR(64) UNIQUE | 调度请求 ID（对外主查询键） |
| business_task_id | VARCHAR(128) | 业务任务 ID（上层 taskflow 的 task_id） |
| rmf_task_id | VARCHAR(128) | 内核任务 ID（兼容保留的字段名，如 `go_to_place.12vlwwh50jq27`） |
| category | VARCHAR(64) | 任务类别（go_to_place/quality_inspection/charge…） |
| robot_code | VARCHAR(64) | 指定/分配的机器人 |
| dispatch_status | VARCHAR(32) | 受理状态：published → rmf_response_received → cancel_requested |
| payload / response / cancel_request | JSONB | 原始请求 / 内核回执 / 取消信息（审计+重放） |
| message | TEXT | 对外消息 |
| scene_code | VARCHAR(64) | 下发时场景快照 |
| is_abandoned | BOOLEAN | 超龄放弃标记（查询呈现终态 failed） |
| archived_at | TIMESTAMPTZ | 归档时间（GC 7200s/1800s） |

### 1.2 t_task_state —— 任务执行状态历史（观测核心表）

**存什么**：每个任务的全部状态事件（queued → underway 分段进度 → 终态），**含失败真实原因和质检结果**，重启不丢，观测台"任务历史"数据源。

| 字段 | 类型 | 说明 |
|---|---|---|
| rmf_task_id | VARCHAR(128) UNIQUE | 内核任务 ID（upsert 键） |
| business_task_id | VARCHAR(128) | 业务任务 ID（关联查询） |
| robot_code | VARCHAR(64) | 执行机器人 |
| status | VARCHAR(32) | queued/underway/completed/failed/canceled |
| category | VARCHAR(64) | 任务类别 |
| **message** | TEXT | **失败原因/进度**（如 `导航分段未完成 [A->B]: 命令回执失败: status=REJECTED`、`导航进度 5/9：已到达 P9`） |
| detail | JSONB | 任务参数 + 动作回执（action_receipt） |
| cancellation | JSONB | 取消标记（fork 语义：completed + labels[reason]） |
| **inspection_result** | JSONB | **质检结果**（机器人回执里的质检数据） |
| updated_at | TIMESTAMPTZ | 最后事件时间 |

### 1.3 t_charger —— 充电桩（资源正本）

**存什么**：充电桩档案 + 实时占用状态（重启不丢占用）。

| 字段 | 说明 |
|---|---|
| charger_id UNIQUE / name | 桩标识 |
| rmf_waypoint / point_code / rmf_level / map_code / scene_code | 桩位置（地图 charge 点位对账同步） |
| resource_type | charger（充电）/ swap_station（换电） |
| compatible_fleets | 兼容车队（TEXT 逗号分隔） |
| **status** | 0=可用 / 1=占用 / -1=异常 |
| **occupied_by / reserved_by** | 占用/预留机器人 |
| enabled | 启用标记 |

数据来源：地图 charge 点位 30s 自动对账（新增 upsert、失效删除、保留动态占用）。

### 1.4 t_map_metadata —— 地图存档

**存什么**：地图元数据 json 正本 + 附件路径。

| 字段 | 说明 |
|---|---|
| map_name + version UNIQUE | 地图名+版本（如 map1022Pro v3.5.6） |
| status | published 才可激活 |
| metadata_json | **单 json 全量地图**（24 点位/29 路径/区域/货架/跨层） |
| pgm_path / yaml_path | 地图图片与 SLAM yaml 附件路径 |

启动加载优先级：DB 激活版本 → 配置文件兜底；上传接口热切换（原子替换运行图）。

### 1.5 t_traffic_event —— 交通事件审计

**存什么**：每次路径段占用/释放流水（交通行为可追溯）。

| 字段 | 说明 |
|---|---|
| robot_code | 机器人 |
| action | ACQUIRE（任务占用）/ RELEASE_SEGMENTS（逐段释放）/ RELEASE（终态全释） |
| resource_id | 段 ID（如 `PATH_012#C1-P8`，对向统一无序点对格式） |
| detail | JSONB 附加信息 |

### 1.6 t_robot_home / t_point / t_scene_resource_bundle —— 预留

家点/场景点位/资源包：**建表未使用**（数据源当前在地图 json 与 RMS inventory，待业务需要时启用）。

### 数据量参考（当前测试环境）

| 表 | 行数 |
|---|---|
| t_task_record | 37 |
| t_task_state | 31 |
| t_traffic_event | 112 |
| t_charger | 2（C1/C2） |
| t_map_metadata | 1 |

## 二、内存镜像与执行上下文

| 数据 | 类 | 说明 |
|---|---|---|
| 机器人实时镜像 | `FleetStateMirror` | 1Hz 状态帧融合（位置/电量/mode/可派），15s 无帧判离线 |
| 任务状态内存镜像 | `TaskStateMirror` | 事件驱动（与 t_task_state 双写，内存供实时查询） |
| **交通占用** | `PersistentTrafficRegistry` | 内存互斥与数据库正本同步更新；重启恢复点段、驻留和停止保留，不清零 |
| 等待意图 | `WaitIntentRegistry` | 占用冲突登记（死锁检测输入，2 分钟过期） |
| 任务执行队列 | `TaskExecutionEngine` | 排队/执行中任务 + per-robot 信号量（重启后未终态任务按中断失败收口，不自动重放；执行标记及资源另行停止对账） |
| 导航图 | `NavigationGraph` | 地图激活时构建（JGraphT），volatile 原子替换 |
| 自动分配等待队列 | pendingAssignments | 无机器人可用时的任务（60s 超时） |
| 返充在途标记 | LowBatteryChargeSupervisor | 防重复返充 |

**读写边界**：实时读取以内存镜像为主；使用权、外部作业、执行标记、交通占用和命令序号同步提交数据库以支持恢复。相同驻留帧不重复落库，真实数据库延迟和容量需单独验收。

## 三、Redis 现状（诚实说明）

**当前状态：已配置连接（boot 依赖 + application.yml），但代码中零使用**（无 RedisTemplate/Redisson 调用）。控制互斥仍在单进程；数据库持久化解决恢复，不提供多活互斥。

**多实例演进时的规划用途**（见 `docs/multi-instance-roadmap.md`）：

| Key | 类型 | 用途 |
|---|---|---|
| robot:scheduler:fleet_state:{fleet} | Hash | 机器人快照镜像（API 副本可读） |
| robot:scheduler:task_state:{id} | String | 任务状态镜像（TTL 10s） |
| robot:scheduler:charger:lock | RLock | 充电桩分配原子性 |
| robot:scheduler:leader | 租约 | 内核单主选主 |
| 交通段占用 | SETNX+TTL | TrafficRegistry Redis 化 |

## 四、数据流转总图

```
任务下发 API ──→ t_task_record（published）
    ↓
内核执行（队列/规划在内存；使用权/交通正本先提交数据库）
    ↓ 事件
TaskStateMirror ──双写──→ t_task_state（queued→underway 进度→终态+失败原因+质检结果）
    ↓
交通行为 ──审计──→ t_traffic_event（ACQUIRE/RELEASE_SEGMENTS/RELEASE）
    ↓
充电桩分配/释放 ──→ t_charger（status/occupied_by）
    ↓
地图上传/激活 ──→ t_map_metadata（json 正本）→ 内存 NavigationGraph
```

## 运行正本新增表（V6/V7）

`t_robot_runtime` 按机器人保存使用权、外部作业、路线预约、实际驻留、停止确认、地图版本、执行任务 ID 和命令序号。写入使用独立事务先提交，再确认内存状态；不自动过期，不随历史任务 GC 清空。

## 观测新增表（V5/V8/V9/V10）

### t_robot_event（V5）—— 机器人事件日志（K8s Events 风格）

事件类型 MODE / BATTERY / ONLINE / **USAGE（OCCUPIED/RELEASED 占用时间线，前端业务流 🔒🔓 数据源）** / MAP（一致性）/ GRAPH（离图）；同因窗口内聚合 count++；保留 7 天小时级 GC。

### t_external_activity（V8/V9）—— 外部动作回执镜像（新版架构核心观测）

上层应用直连 RMS 的业务动作（质检/充电）回执存档：robot_code、body_task_id（=移动任务 task_id，聚合键）、rms_command_id（幂等去重）、action_type、action_status（3 成功/5 取消/6 失败）、result_report（业务结果 JSONB）、**failure_msg（V9，失败原因如"任务模板不存在"）**。调度镜像落档后兜底 ack 清 RMS 信箱。

### t_traffic_event.resource_id（V10）

`resource_id` 扩为 TEXT（一次事件完整资源列表逗号拼接，可超 128 字符）。

数据库使用 TIMESTAMPTZ，接口继续使用 Unix 毫秒。新增迁移不改旧表；首次从纯内存版本升级需要停单和确认所有机器人停止。恢复机制与部署边界见 [acceptance-and-recovery.md](acceptance-and-recovery.md)。
