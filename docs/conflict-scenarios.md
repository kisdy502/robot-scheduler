# 调度冲突场景与解决方案手册

> 本文档覆盖调度系统的全部交通冲突场景、解决算法、代码位置与错误提示。
> 错误提示通过任务状态接口的 `message` 字段返回：`GET /api/v1/scheduler/tasks/status?scheduler_request_id=` 或 `GET /api/v1/observability/tasks`。

## 冲突场景总表

| # | 场景 | 解决算法 | 代码位置 | 错误提示（message） |
|---|---|---|---|---|
| L1 | 路线被**执行中任务**占用（机器人在路上走） | 整条路径原子预占 + 他机规划绕行 | `TaskExecutionEngine.acquireTraffic` + `NavigationGraph.plan` | 无需提示（自动绕行/排队） |
| L2 | 路线被**静止机器人**占据（故障/停靠在两点之间的路段中间） | 驻留段障碍化：idle 机器人吸附到段时登记驻留段 → 进入 occupiedSegments → 他机规划绕行/无路等待 | `FleetManager.updateDwellState`（段吸附分支）+ `InMemoryTrafficRegistry.updateDwell/dwellSegmentOwner` | 超时：`交通资源占用失败（等待 300s 超时）: ...路径被其他机器人长期占用` |
| S1 | 目标点被其他**执行中任务**占用 | 排队等待：任务占用失败循环重试，占用方完成后自动获得 | `TaskExecutionEngine.acquireTraffic` | 等待中无提示（任务 underway），超时后见 S4 |
| S2 | 目标点/必经点被**静止机器人**驻留（场景一） | 驻留点占用：idle 机器人每帧吸附点注册为障碍；规划层过滤驻留点 → 无路可达 → 持续等待驻留者离开 | `FleetManager.updateDwellState` + `InMemoryTrafficRegistry.updateDwell` + `NavigationGraph.plan(blockedWaypoints)` | `交通资源占用失败（等待 300s 超时）: 目标点 R1 被静止机器人 RB_XX 驻留占用` |
| S3 | 路径段被占（网状拓扑可绕行） | Dijkstra 规划时排除被占段，自动绕行 | `NavigationGraph.plan + filterGraph` | 无需提示（自动绕行成功） |
| S4 | 路径资源长期被占（等待超时） | 5 分钟持续等待后失败 | `TaskExecutionEngine.acquireTraffic`（TRAFFIC_WAIT_LIMIT_SECONDS=300） | `交通资源占用失败（等待 300s 超时）: 目标点 X 或必经路径被其他机器人长期占用` |
| S5 | **单线对向**（场景二：A 去点2、B 去点1，仅一条路） | 对向段互斥：双向路径统一段 ID（无序点对 `PATH_x#{min,max}`），后到者等待先到者通过并释放 | `NavigationGraph.segmentId`（段 ID 生成） | 后到者排队等待；超时同 S4 |
| S6 | 死锁环（A 等 B 的段、B 等 A 的段） | 死锁检测：等待意图图 DFS 环检测告警 + 等待超时自然打破（任务失败释放资源） | `DeadlockMonitor.detect` + `WaitIntentRegistry` | 日志：`检测到交通死锁环: [A, B]`；任务超时同 S4 |
| S7 | 全部机器人忙/不可派 | 自动分配排队 5s 重试，60s 超时 | `TaskExecutionEngine.retryPendingAssignments` | `自动分配超时：无可用机器人在 60s 内` |
| S8 | 指定机器人离线/离图/外部任务中 | 机器人就绪等待 30s 超时 | `TaskExecutionEngine.waitForRobotReady` | `机器人不可派发（离线/离图），等待超时` |
| S9 | 目标点位不存在 | 受理时立即校验 | `TaskExecutionEngine.executeGoal` | `目标点位不存在: XX999` |
| S10 | 机器人导航被拒（RMS/机器人本体） | 命令重试 3 次后失败，回执原因透传 | `RobotCommandExecutor` + `TaskExecutionEngine` | `导航分段未完成 [A->B]: 命令回执失败: status=REJECTED`（含机器人返回的真实原因） |
| S11 | 地图热切换 | 旧图交通占用全量清理 | `MapSwitchRuntimeCleaner` | 无提示（自动清理） |

## 两大死锁场景详解

### 场景一：死端必经点被静止机器人堵住

```
P1 ---- R1 ---- P6(死端)
        ↑
     机器人B静止停靠
```

**问题**：机器人 A 在 P1 想去 P6，R1 是唯一必经点，B 静止停在 R1 → A 必然堵死。

**解决链路**（三层防线）：
1. **驻留登记**（`FleetManager.updateDwellState`）：B 静止时每帧把吸附点 R1 注册为驻留占用
2. **规划规避**（`NavigationGraph.plan(blockedWaypoints)`）：A 规划时 R1 被过滤 → P6 无路可达 → A 进入持续等待（不冒然下发导航）
3. **动态解除**：B 开始移动（上位机操控或新任务）→ FleetManager 检测到 mode≠idle → 清除驻留 → A 的下一轮重试规划成功 → 任务继续

**等待上限**：300 秒。B 一直不动 → A 失败，提示：
```
交通资源占用失败（等待 300s 超时）: 目标点 P6 被静止机器人 RB_XX 驻留占用
```

### 场景二：两台机器人对向互换位置

**情况 A：只有一条路（单线）**
```
A →→→ 点1 ===== 单线路径 ===== 点2 ←←← B
```
**解决**：对向段互斥——双向路径的正反两个方向共享同一个段 ID（`PATH_x#WP1-WP2`，无序点对），A 先申请成功，B 被拒 → B 持续等待 → A 到达点2 释放段 → B 通行。**不会面对面堵死**。

**情况 B：有多条岔路（网状）**
**解决**：B 规划时 A 占用的段在封锁列表中 → Dijkstra 自动绕行（实测验证：T1→W1 与 H1→P1 对向长路径，网状图自动错峰/绕行，双任务完成）。

**等待上限**：300 秒超时同 S4。

## 核心算法与代码索引

| 算法 | 类/方法 | 位置 |
|---|---|---|
| 交通占用（段+点，原子申请/释放） | `InMemoryTrafficRegistry.tryAcquire/releaseSegments/releaseAll` | `core/traffic/InMemoryTrafficRegistry.java` |
| 驻留点占用（静止机器人停在点上） | `InMemoryTrafficRegistry.updateDwell/dwellOwner` | 同上 |
| 驻留段占用（静止机器人停在路段中间） | `InMemoryTrafficRegistry.updateDwell/dwellSegmentOwner` | 同上 |
| 驻留驱动（每帧吸附点/段登记，移动即清除） | `FleetManager.updateDwellState` | `core/fleet/FleetManager.java` |
| 对向段互斥（段 ID 无序点对） | `NavigationGraph.segmentId` | `core/routing/NavigationGraph.java` |
| 规划规避（封锁段+封锁点过滤） | `NavigationGraph.plan/filterGraph` | 同上 |
| 持续等待（冲突循环重试 300s） | `TaskExecutionEngine.acquireTraffic` | `core/task/TaskExecutionEngine.java` |
| 分段导航（逐点下发+逐段释放） | `TaskExecutionEngine.navigateBySegments` | 同上 |
| 死锁环检测（DFS） | `DeadlockMonitor.detect` | `core/traffic/DeadlockMonitor.java` |
| 精准错误描述 | `TaskExecutionEngine.describeTrafficBlock` | `core/task/TaskExecutionEngine.java` |

## 验证状态

| 场景 | 验证方式 | 状态 |
|---|---|---|
| S1 排队等待 | 真机 5 mock 多轮任务 | ✅ 已验证 |
| S2 死端驻留 | `TrafficDeadlockScenarioTest.scenario1_deadEndBlockedByDwellingRobot`（P1-R1-P6 死端拓扑，B 驻留 R1 → 规划无路 → B 离开恢复） | ✅ 单测覆盖 |
| S3 绕行 | `NavigationGraphTest.plan_blockedSegment_detoursAround` + 真机对向长路径 | ✅ 已验证 |
| S5 对向互斥 | `TrafficDeadlockScenarioTest.oppositeDirectionSegments_areMutuallyExclusive`（A 占去程，B 对向被拒；A 释放后 B 可通行） | ✅ 单测覆盖 |
| L2 驻留段占用 | `TrafficDeadlockScenarioTest.dwellSegment_blocksOtherRobotsPathAcquire`（B 停段中间 → A 路径被拒；B 移动恢复） | ✅ 单测覆盖 |
| S6 死锁环 | `DeadlockMonitorTest`（双向/三向环检测） | ✅ 告警级 |
| S7/S8/S9/S10 | 真机多轮测试 | ✅ 已验证 |

## 待增强（P6）

- **让路点导航**（死锁无损打破）：检测到死锁环时，牺牲者自动导航至最近 holding_point 让路，而非等待超时失败
- **对向通行预约**（同段对向排队）：单线路对向等待时按到达先后排队放行（当前为先占先得）
- **死锁主动告警接口**：DeadlockMonitor 检测结果推送观测页面
