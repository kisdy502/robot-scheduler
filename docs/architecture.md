# 调度系统架构总览

> Java 21 + Spring Boot 3.3.5 ｜ 端口-适配器架构 ｜ 单进程内核（多实例演进见 multi-instance-roadmap.md）

## 〇、新版架构定调（2026-11-04 起）

**调度只管移动。** 一次任务 = 规划路径 → 导航到点 → 交通管制/点位占用 → 完成。
质检、充电启动、夹爪等业务动作（start-activity）由**上层应用直连 RMS 下发**，调度永不代发；
调度对业务动作只做**观测镜像**（回执存档 + 兜底 ack），不编排不触发。

| 触发入口 | 占用/释放 | 移动 | 业务动作 |
|---|---|---|---|
| 上层应用（前端模拟器 / 未来 taskflow 服务） | assign-task / release-robot | goto-place（统一入口） | 直连 RMS start-activity |
| 地图页右键 | 同上（前端编排） | 同上 | 无 |
| 低电自动回充（≤20%） | 调度内部 | 移动到充电位 | 无（本体自充） |

**任务 ID 契约**（真机端要求）：`taskflow_id` = 数据库雪花 ID（assign-task 为 Long 整数）；
`task_id` = `taskflow_id + yyyyMMddHHmmssSSS`（17 位时间后缀，直接反映发布先后顺序）。

`dispatch_task` 已废弃（@Deprecated，端点保留兼容旧上层）；`goto-place` 的 taskflow_id/task_id 已放宽为 String（兼容旧整型 ID）。

## 一、模块总览

```
robot-scheduler/
├── robot-scheduler-common       基础协议（零 Spring 依赖）
├── robot-scheduler-core         调度内核（算法全部在此，禁止依赖 web/db）
├── robot-scheduler-rms-client   RMS 适配（实现 core 端口）
├── robot-scheduler-persistence  持久化（实现 core 端口，Flyway V1-V10）
├── robot-scheduler-api          REST 层（兼容端点 + 观测 + 地图管理）
└── robot-scheduler-boot         启动装配
```

**依赖方向**：common ← core ← {rms-client / persistence / api} ← boot。core 只定义端口，实现在外围。

| 模块 | 职责 | 核心类 |
|---|---|---|
| **common** | Result/ErrorCode/分页 + 领域字典 | `SchedulerConstants`、`TaskStatus`、`TaskCategories`、`RobotMode` |
| **rms-client** | RMS HTTP 客户端 + Mock | `HttpRobotManagementClient`（信封双兼容/三态映射/stop 幂等）、`RmsRobotGateway`、`RmsRobotStateSource` |
| **persistence** | MyBatis-Plus 全 Lambda 零 XML + Flyway | 各 Repository 实现、`DbChargerPool`、`DbTrafficEventSink` |
| **api** | Controller + 全局异常 + 观测 | `SchedulerTaskController`、`SchedulerResourceController`、`RuntimeController`、`RobotUseController`、`ObservabilityController`、`MapController` |
| **boot** | 启动 + 装配 | `MapBootstrapRunner`、`TaskStatePersistenceBinder`、`TrafficAuditBinder`、`MapSwitchRuntimeCleaner` |

## 二、调度内核（core）—— 算法详解

### 2.1 map 包 —— 地图管理

| 类 | 职责 |
|---|---|
| `MapMetadataParser` | 单 json 元数据解析 + 结构校验（独立 ObjectMapper） |
| `MapManager` | 地图生命周期：任务绑定与受控切换（单快照原子发布） |

- 上传/激活门禁：有任务绑定或交通占用（含驻留）时拒绝切图（HTTP 409 + Result 60409）
- 启动加载优先级：DB 激活版本 → 配置文件兜底；恢复占用所需地图版本不一致时**停止启动**（防换图后占用语义错乱）
- 换图标准操作：换 local-map 配置后 `TRUNCATE t_robot_runtime` 清运行时登记再启动

### 2.2 routing 包 —— 路径规划

| 类 | 职责 |
|---|---|
| `NavigationGraph` | 楼层规划图（JGraphT Dijkstra）：`plan(floorId, from, to, speed, blockedSegments, blockedWaypoints)`；`attach` 位置吸附（点 3m/段 1m） |
| `GeometryUtil` | 平面几何：禁区判定/点在多边形/段吸附垂距 |

算法要点：限速取车道/区域/调度上限三者最小；禁区段不建边；贝塞尔 16 段折线积分；**封锁段/点在搜索前从图排除**（被占资源=墙，规划绕行）；段 ID 无序点对格式 `PATH_x#{min}-{max}`（双向对向互斥）。

> 已知局限（升级候选）：被占点是"墙"不是"红灯"——近路被占即绕远路，无等待代价权衡。见 rmf-traffic-analysis.md 第五节。

### 2.3 traffic 包 —— 交通管制

| 类 | 职责 |
|---|---|
| `TrafficRegistry` | 占用端口（活动租约段+点 / 驻留点 / 驻留段三级） |
| `PersistentTrafficRegistry` | 单活正式实现，运行正本（t_robot_runtime）同步持久化 |
| `DeadlockMonitor` | 循环等待组检测（强连通分量，5s 周期，告警级不自动解） |
| `WaitIntentRegistry` | 等待意图登记（死锁检测输入） |
| `ExternalActivityMirror` | **外部动作回执镜像**：RMS 信箱里非调度下发的回执 → t_external_activity 存档（含 failure_msg）→ 返回 ID 供 FleetManager 兜底 ack 清信箱 |

**占用生命周期**（前锁后放）：
```
任务受理 → tryAcquire 途经点全量持有 + 段租约（防他人把途经点当目标）
  → 逐段导航，每到达一个途经点 releaseBehind 释放身后（渐进）
  → 段入口 awaitSegmentEntryClear 复检（防等待期被驻留抢占）
  → 正常到达 releaseAllAndDwell 原子转目标驻留（消灭释放/驻留间幽灵窗口）
  → 异常/取消/失联：保留占用，stop 幂等确认后转实际驻留
静止机器人 → FleetManager 每帧 updateDwell（吸附点 0.8m/段），移动即清除
```

**stop 幂等语义**：`NO_ACTIVE_TASK` 与 `BODY_STOP_TASK_REJECTED + already failed/finished`（本体任务已终态，无可停之物）均归一为幂等成功——否则会陷入 5s 重发死循环、交通占用永久保留（2026-11-03 真机事故修复）。

详见 [traffic-control.md](traffic-control.md)。

### 2.4 task 包 —— 任务执行

| 类 | 职责 |
|---|---|
| `TaskExecutionEngine` | 内核执行引擎（虚拟线程）：submit/submitForAutoAssign/cancel；**分段导航 + 到位复核 + 交通闭环** |
| `TaskStateMirror` | 任务状态镜像（内存+落库双写，10s TTL 判 stale） |
| `TaskGoal` | 执行目标——**action 恒为 null（纯移动）**，旧动作执行代码保留但不可达 |

**任务全链路**：
```
POST /goto-place → facade 校验占用归属+幂等 → 落库(t_task_record, published)
  → engine.submit → queued → 就绪等待(30s) → underway
  → NavigationGraph.plan（避开占用段/驻留点）
  → tryAcquire 途经点全量持有（冲突 8s 间隔 TRAFFIC_WAIT 事件，300s 上限）
  → 逐段 navigate（description 只带对账字段：rmf_task_id/task_id/taskflow_id/nav_target_point，
    模板字段已剥离——真机对 description.task_template_code 强校验会拒绝 navigate）
    每段：等回执（executor uid/cmd_id 匹配 + ack）→ confirmArrivalDeviation 到位复核（0.20m）
  → releaseAllAndDwell 原子驻留 → completed
```

- 取消 fork 语义：排队 → canceled；执行中 → stop + completed + cancellation 标记
- 失败原因全链路透传：回执 message → 事件 → 落库 → 接口

### 2.5 fleet 包 —— 机器人管理

| 类 | 职责 |
|---|---|
| `FleetManager` | 1Hz 状态帧 + 0.1Hz 清单轮询融合中心：驻留登记/充电桩位置释放/停止确认/回执信箱分发（先镜像后认领） |
| `RobotCommandExecutor` | 每机器人串行命令执行器：重试 3 次退避；回执匹配 + ack；`pendingCmdId`（供镜像区分内外部命令——真机 uid 不回显 sched: 前缀） |
| `TaskAssignmentRegistry` | 使用权登记（assign/release + USAGE 事件进 t_robot_event） |
| `RobotEventMonitor` | 机器人事件日志（MODE/BATTERY/ONLINE/USAGE/MAP/GRAPH，K8s 式聚合，7 天 GC） |

### 2.6 assign 包 —— 任务分配（自动分配场景）

`CostBasedTaskAssigner`：代价 = ETA + 电量惩罚 + 忙碌惩罚；`ChargingInterruptionPolicy` 决定充电机可否被打断（预计任务后电量 ≥ 返充线 30%+余量，打断代价 120s）。
> 新版架构默认"机器人选定不分配"（上层编排），自动分配保留给无 robot_code 的 goto-place。

### 2.7 charger 包 —— 充电管理

`LowBatteryChargeSupervisor`：30s 扫描，idle 且电量≤20% → 自动生成 **charge 移动任务**（纯导航到桩，不发 start-activity；本体到位自充）。充电桩基于位置自动释放（离开即释放）。

### 2.8 dispatch 包 —— 门面与治理

`SchedulerFacade`：受理校验链（占用归属/幂等/时间/fleet 一致性）+ `buildGoal`（**恒纯移动，不构造 action，不注入模板字段**）。
`RecordGcJob`：60s GC（published 1800s 放弃；7200s 未更新归档）。

## 三、观测体系（新版架构的"业务可见性"）

调度不再执行业务动作，但**观测总线上的全部事实**：

| 数据源 | 表/接口 | 内容 |
|---|---|---|
| 移动任务 | t_task_record + t_task_state | 全链路受理/执行/失败原因 |
| 占用/释放 | t_robot_event（USAGE/OCCUPIED/RELEASED） | 上层业务流的 🔒🔓 时间线 |
| **外部动作镜像** | t_external_activity（V8/V9） | 上层直连 RMS 的质检/充电回执（result_report + failure_msg），按 body_task_id 与移动任务聚合 |
| 交通审计 | t_traffic_event（V10 resource_id TEXT） | 段/点占用释放流水 |
| 实时快照 | /runtime/fleets/snapshot、/observability/** | 前端观测台数据源 |

前端任务历史页按 task_id 聚合三类记录展示完整业务流时间线（🔒抢占→🚗移动→🔬质检→🔓释放）。

## 四、扩展指南

| 需求 | 改哪里 |
|---|---|
| 新业务动作（夹爪/拍照…） | **不改调度**——上层直连 RMS start-activity，镜像自动捕获回执 |
| 换 RMS 协议 | rms-client 实现 RobotGateway/RobotStateSource 端口 |
| 换存储 | persistence 实现 Repository 端口 |
| 多实例部署 | 见 multi-instance-roadmap.md |
| 交通升级（红灯规划/让路） | 见 rmf-traffic-analysis.md 第五节路线 |

## 五、测试

当前全量 **349 用例**（core 294 + api 8 + rms-client 14 + boot 27 + 其他 6），Mockito 隔离、不拉 Spring 上下文；关键回归：30 台工作流闭环、重启恢复、交通死锁场景、外部动作镜像。测试阅读工具见 unit-test-review.md。
