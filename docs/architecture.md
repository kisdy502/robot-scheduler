# 调度系统架构总览

> Java 21 + Spring Boot 3.3.5 ｜ 端口-适配器架构 ｜ 单进程内核（多实例演进方案见 multi-instance-roadmap.md）

## 一、模块总览

```
robot-scheduler/
├── robot-scheduler-common       基础协议（零 Spring 依赖）
├── robot-scheduler-core         调度内核（算法全部在此，禁止依赖 web/db）
├── robot-scheduler-rms-client   RMS 适配（实现 core 端口）
├── robot-scheduler-persistence  持久化（实现 core 端口）
├── robot-scheduler-api          REST 层（17 兼容端点 + 观测 + 地图管理）
└── robot-scheduler-boot         启动装配
```

**依赖方向**：common ← core ← {rms-client / persistence / api} ← boot。core 只定义端口，实现在外围——换 RMS 协议或换存储不动内核。

| 模块 | 职责 | 核心类 |
|---|---|---|
| **common** | 统一响应 Result/ErrorCode(6xxxx)/分页 + 调度领域字典 | `SchedulerConstants`（旧栈常量对齐）、`TaskStatus`（状态归一化）、`TaskCategories`（类型归一化）、`RobotMode`（0-11 模式枚举） |
| **rms-client** | RMS 8 端点 HTTP 客户端 + Mock | `HttpRobotManagementClient`（信封双兼容/三态映射）、`RmsRobotGateway`（命令适配）、`RmsRobotStateSource`（状态源适配） |
| **persistence** | MyBatis-Plus 全 Lambda 零 XML + Flyway | `MybatisTaskRecordRepository`、`MybatisTaskStateRecordRepository`、`DbChargerPool`、`DbTrafficEventSink` |
| **api** | Controller + 全局异常 + 观测台 | `SchedulerTaskController` 等 3 个兼容 Controller、`ObservabilityController`、`MapController`、`ApiExceptionHandler`（FastAPI detail 格式） |
| **boot** | 启动 + 装配 | `MapBootstrapRunner`、`TaskStatePersistenceBinder`、`TrafficAuditBinder`、`MapSwitchRuntimeCleaner` |

## 二、调度内核（core）—— 算法详解

### 2.1 map 包 —— 地图管理

| 类 | 职责 | 关键方法 |
|---|---|---|
| `MapMetadataParser` | 单 json 元数据解析 + 结构校验（独立 ObjectMapper，防全局 snake_case 污染） | `parse(json)` → MapMetadata；校验 version/published/点位唯一/路径引用完整 |
| `MapManager` | 地图生命周期：热切换（上传即激活，volatile 原子替换） | `activateFromJson`（上传激活+存档）、`loadFromArchive`（启动 DB 优先）、`activeGraph()`（获取运行图）、`activeFloorId()` |
| `MapMetadata` 等 | 领域模型（严格对齐地图编辑器 json 契约） | 版本状态机（仅 published 可激活）、13 种点位类型、曲线段（贝塞尔）、区域约束 |

**使用**：`POST /api/v1/maps/upload` 上传 → MapManager 激活 → 触发 `MapSwitchRuntimeCleaner` 清空旧图交通占用。

### 2.2 routing 包 —— 路径规划

| 类 | 职责 | 关键方法 |
|---|---|---|
| `NavigationGraph` | 楼层规划图（JGraphT Dijkstra） | `plan(floorId, from, to, speed, blockedSegments, blockedWaypoints)` → 最短路；`attach(floorId, x, y)` → 位置吸附（点 3m/段 1m）；`waypointByIdOrName`（id/name 双解析） |
| `GeometryUtil` | 平面几何（自研零依赖） | `segmentIntersectsPolygon`（段-多边形相交，禁区判定）、`pointInPolygon`（射线法）、`pointToSegmentDistance`（段吸附垂距） |
| `NavigationRoute` | 规划结果（途经点序列+段信息+ETA） | `Segment`（段 ID/长度/限速/避障标志） |

**算法要点**：
- 路径未设置限速时使用楼层默认速度，再与相交区域限速取最小值；查询时合并调度导航速度上限，搜索边权、ETA、规划结果段速度和 RMS `speed_limit` 使用同一有效速度
- `robot.scheduler.assign.navigation-max-speed-mps`（默认 1.0，可通过 `SCHEDULER_NAVIGATION_MAX_SPEED_MPS` 覆盖）用于分配与执行；当前协议没有逐机速度能力字段，此配置是调度上限，不能替代硬件速度能力校验
- 导航重试保留段限速，段超时预算同时考虑限速；楼层/路径速度必须有限且大于零，区域速度允许零表示禁行
- 禁区（forbidden）相交的段**不建边**（剪枝而非过滤）；减速区合并限速
- 贝塞尔曲线段按 16 段折线积分计长
- 封锁段/点在**搜索前**从图排除（而非事后检查），保证绕行正确
- 段 ID 无序点对格式 `PATH_x#{minId}-{maxId}`——双向路径对向互斥

### 2.3 traffic 包 —— 交通管制（调度核心）

| 类 | 职责 | 关键方法 |
|---|---|---|
| `TrafficRegistry` | 占用端口（段+点+驻留三级） | `tryAcquire(robot, segments, waypoints, lease)` 原子占用；`releaseSegments` 渐进释放；`updateDwell(robot, wpId, segId)` 驻留登记；`occupiedSegments/Waypoints` 规划障碍输入 |
| `InMemoryTrafficRegistry` | 默认实现（synchronized 单锁，<500 台够用） | 冲突判定含：任务占用互斥 + 驻留点/段互斥 + 对向段互斥（统一 ID 天然实现） |
| `DeadlockMonitor` | 死锁环检测（等待意图图 DFS，5s 周期） | `detect()` → 环列表（目前告警级，超时自然打破） |
| `WaitIntentRegistry` | 等待意图登记（占用失败时记录，死锁检测输入） | `record(robot, segments)` / 2 分钟自动过期 |

**占用生命周期**：
```
任务受理 → tryAcquire 整条路径（全有或全无，冲突循环等待 300s）
  → 逐段导航，每到达一个途经点 releaseSegments 释放身后段（渐进）
  → 任务终态 releaseAll 全量释放
静止机器人 → FleetManager 每帧 updateDwell（吸附点+段），移动即清除
```

### 2.4 task 包 —— 任务执行

| 类 | 职责 | 关键方法 |
|---|---|---|
| `TaskExecutionEngine` | 内核执行引擎（虚拟线程/任务） | `submit(record, robot, goal)` 指定机器人执行；`submitForAutoAssign(record, goal, fleet, group)` 自动分配；`cancel(taskId, record, robot, reason)` 取消（fork 语义）；`retryPendingAssignments`（5s 周期分配重试） |
| `TaskStateMirror` | 任务状态镜像（内存+落库双写） | `onTaskStateEvent`（事件驱动 upsert t_task_state）；`isStale`（10s TTL 判定）；`evictTerminalOlderThan`（GC） |
| `TaskStateEvent` | 事件契约（旧 task_state_update 同构） | 携带 message（失败真实原因）+ schedulerRequestId（回填 rmf_task_id） |
| `TaskGoal` | 执行目标（导航点+动作+参数） | 由 facade 按 task_template_type 构建 |

**任务状态机**：queued → underway（分段进度事件）→ completed / failed / canceled
- 取消 fork 语义：排队中 → canceled；执行中 → 机器人 stop + completed + cancellation 标记
- 分段导航：按规划途经点逐段下发 navigate（对齐旧 RMF），每段独立超时（段长/保守速度+余量）
- 失败原因全链路透传：回执 message → CommandResult.detail → 事件 message → 落库 → 接口

### 2.5 fleet 包 —— 机器人管理

| 类 | 职责 | 关键方法 |
|---|---|---|
| `FleetManager` | 状态帧融合中心（1Hz 状态 + 0.1Hz 清单轮询） | `onStateTick`（融合+mode 推导+驻留登记+充电桩位置释放）；`updateMovingState`（外部任务保底：帧间位移>0.3m 判 busy）；`sweepStaleRobots`（15s 无帧下线）；`updateDwellState`（静止机器人障碍化） |
| `RobotCommandExecutor` | 命令执行器（每机器人串行） | `submitNavigation/submitActivity`（重试 3 次退避 1s）；`onCompletedCommands`（回执匹配：uid/cmd_id 三级 + ack）；`abandon`（超时/取消防迟到回执误伤） |
| `RobotGateway` | 命令端口（rms-client 实现） | navigate/stop/startActivity/pauseTask/resumeTask/ackCompletion |
| `RobotStateSource` | 状态源端口（rms-client 实现） | fetchStates/fetchInventory |
| `FleetStateMirror` | 机器人镜像 | `list/filter/dispatchable 判定` |

### 2.6 assign 包 —— 任务分配

| 类 | 职责 | 算法 |
|---|---|---|
| `CostBasedTaskAssigner` | 集中分配（替代 RMF bidding） | 代价 = 规划 ETA + BatteryAware 电量惩罚（<30% 线性、<10% 二次加重）+ 忙碌惩罚（300s）；候选过滤：在线/可派/非忙/fleet 匹配 |

### 2.7 charger 包 —— 充电管理

| 类 | 职责 |
|---|---|
| `LowBatteryChargeSupervisor` | 30s 扫描：idle 且电量≤20% → 自动生成 charge 任务（充电桩分配+destination 注入+防重入+终态/离位双重释放） |
| `ChargerPoolPort` / `DbChargerPool` | 充电桩池（DB 正本+内存缓存）；地图 charge 点位 30s 对账 |

### 2.8 dispatch 包 —— 门面与治理

| 类 | 职责 |
|---|---|
| `SchedulerFacade` / `DefaultSchedulerFacade` | 17 兼容端点门面（envelope/detail/字段保留）+ 任务校验链（类型归一/end_action/时间/fleet 一致性/return_home） |
| `RecordGcJob` | 60s 周期 GC：published 无回执 1800s 放弃归档；7200s 未更新归档；镜像成对清理 |
| `SchedulerHttpException` | HTTP 语义异常（400/404/409/501 → FastAPI detail 格式） |

## 三、关键流程

### 任务执行全链路
```
POST /dispatch_task → facade 校验+落库(t_task_record, published)
  → engine.submit → queued 事件（异步，保持响应 published 语义）
  → [指定 robot] 直接执行 / [自动] CostBasedTaskAssigner 选机（分配后立即置 running 防并发堆积）
  → per-robot 信号量排队 → waitForRobotReady(30s) → underway 事件
  → NavigationGraph.plan（避开占用段/驻留点）
  → TrafficRegistry.tryAcquire 整路径（冲突循环等待 300s）
  → 逐段 navigate（RobotCommandExecutor 重试+回执匹配）
    每段完成：releaseSegments + underway 进度事件（导航进度 2/5：已到达 X）
  → [有动作] submitActivity → 回执 receipt → completed 事件（含质检结果）
  → releaseAll + t_task_state 落库（失败原因/质检结果可查）
```

### 交通冲突处理矩阵
见 `docs/conflict-scenarios.md`（12 场景全覆盖：任务占用/驻留点/驻留段/对向互斥/死锁环/禁区绕行）。

## 四、扩展指南

| 需求 | 改哪里 |
|---|---|
| 新任务类型 | `TaskCategories` 加常量 + `DefaultSchedulerFacade.buildGoal` 加 case + 引擎无需改（导航+动作组合自动执行） |
| 换 RMS 协议 | rms-client 实现 `RobotGateway`/`RobotStateSource` 端口，core 不动 |
| 换存储 | persistence 实现 Repository 端口，core 不动 |
| 多实例部署 | 见 `docs/multi-instance-roadmap.md`（Redis 快照+选主+占用 Redis 化） |
| 新地图 | 遵循《地图编辑json文件格式.md》，上传接口激活（热切换） |

## 五、测试与文档索引

| 文档 | 内容 |
|---|---|
| `docs/api-test-guide.md` | HTTP 接口测试手册（PowerShell 命令+多机 mock 接入） |
| `docs/conflict-scenarios.md` | 冲突场景矩阵（场景/算法/代码位置/错误提示） |
| `docs/multi-instance-roadmap.md` | 多实例演进方案 |
| 测试 | 166 个：契约 20 + 引擎 13 + 命令 10 + 车队 16 + 分配 8 + 返充 7 + 死锁场景 13 + 地图/规划 22 + facade 60 + DB 6 |
