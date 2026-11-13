# RMF 交通管制逻辑梳理 与 交管模块升级设计

> 源码考古范围：`D:\robotscheduler\open-rmf\src\open-rmf\rmf\`（rmf_traffic / rmf_ros2::rmf_fleet_adapter）
> 本文目标：吃透 RMF 交管算法 → 对照我们现状 → 给出交管独立模块的升级路线。

---

## 一、RMF 交管全景：三件套分工

```
┌────────────────────────────────────────────────────────────────┐
│  schedule（规划期：时空预约表 + 谈判）                            │
│  Participant.set(itinerary 带时间轨迹) → Database(预约表)        │
│  冲突 → Negotiation(谈判桌) → SimpleNegotiator(改道/推迟/让路)    │
├────────────────────────────────────────────────────────────────┤
│  agv::Planner（桥：时空 A* 消费预约表）                           │
│  RouteValidator 剪枝 + hold 等待作为搜索原子动作 + Rollout 备选    │
├────────────────────────────────────────────────────────────────┤
│  blockade（执行期：窄通道分步放行）                               │
│  path 切 checkpoint → Moderator 授予 ReservedRange → 随走随放    │
└────────────────────────────────────────────────────────────────┘
```

- **schedule** 解决"大家的计划时空上冲突怎么办"（规划期，谈判消解）
- **blockade** 解决"执行时这段路谁先过"（运行期，checkpoint 授权）
- **planner** 是两者的桥：用预约表当硬约束搜出无冲突计划
- 两者仅通过 ParticipantId 复用关联，数据面正交

---

## 二、blockade：执行期分步放行协议

### 2.1 角色

| 角色 | 职责 |
|---|---|
| `Writer`（抽象） | 上行通道五原语：set / ready / reached / release / cancel |
| `Moderator : Writer` | 中央调解器：几何冲突分析 → 约束编译 → 授予求解 |
| `Participant` | 机器人侧句柄：本地状态机（单调性保护，旧消息丢弃） |
| `Rectifier` | 不可靠网络纠偏：状态对账 + 差异重传 + 死者清理 |

### 2.2 核心数据结构

```cpp
struct Checkpoint { position; map_name; can_hold; }   // 路径拐点即 checkpoint（切分由规划器完成）
struct ReservedRange { size_t begin, end; }            // 停在 begin，允许走到 end，[begin,end) 即独占空间段
struct Status { reservation_id; last_ready; last_reached; critical_error; }
```

- `can_hold=false`（窄道中间）的点不能停车；冲突区会**向前扩张到最近可停点**——机器人永远被拦在停得住的地方
- `reservation_id` 模 2^64 环序比较，抗旧消息重放

### 2.3 状态机

```
set(path)     → range={0,0}，哪都不许走
ready(k)      → 请求出发，进 ready_queue 等授予
  授予        → range.end 扩到 ≤ k+1（贪心收缩试探）
reached(n)    → 校验 n≤end（越界=critical_error）；range.begin=n（身后释放，连锁解禁他人）
release(c)    → 撤回 ready 让路：end 收缩，立即给别人腾空间
reached(末点) → begin==end，完成；cancel 摘除全部约束重算
```

### 2.4 授予求解（三层）

1. **几何冲突提取**：新旧路径两两段×段检测（半径膨胀 r_a+r_b），夹角≤5° 判 Alignment（顺行可保序同走）否则 Conflict（互斥）；产出 Bracket 区间对并合并
2. **约束编译**：Conflict → `blockage` 布尔约束（"对方停在冲突区外 **h**old" 或 "对方已 **p**assed" 我才可走）；Alignment → `behind` 时序约束（同向保序跟车）；全部 AND 成 `should_go[p][c]`
3. **授予**：ready(k) 后试探性扩张 range，每个新覆盖 checkpoint 的约束以**全体 ranges 快照**求值；可行即授，不行留队等他人状态变化触发重扫

### 2.5 死锁三层防线

- **预防**：约束编译期做环检测（"我停⇒你堵⇒…⇒回到我"），环编译成 OrConstraint AND 进所有 should_go——**环形等待状态在授予阶段就被禁止**
- **让路**：release 原语主动退让（配合 can_hold 点）
- **兜底**：cancel 摘除（警告：机器人物理还在共享区时 cancel 可能造成永久死锁，责任上移调用方）

无优先级/防饥饿机制（FIFO ready_queue，活性靠"有限路径集合必然收敛"的测试保证）。

---

## 三、schedule：规划期预约表 + 谈判

### 3.1 预约表（Database）

- **资源模型 = 连续时空几何**（不是离散 lane）：轨迹（带时间的样条）+ Profile 凸形状（footprint 物理占用 / vicinity 敏感区，相交即干扰=参数化安全间距）
- **Timeline 索引**：地图 → 1 分钟时间桶 → RouteEntry；查询走时空粗筛 + FCL 凸形扫掠精筛
- **三层版本号**：全局 schedule_version（增量同步）/ 每参与者 ItineraryVersion（幂等乱序）/ ProgressVersion；modular 环序比较 + **InconsistencyTracker 缺口暂存回放**（丢包容忍）
- **cull 清扫**（过期轨迹回收）+ cumulative_delay 上限（防延迟历史膨胀）——长期运行内存安全双保护

### 3.2 Negotiation 谈判（重点）

**触发**：上游（schedule 节点）检测到新轨迹与库内行程冲突 → 对涉事参与者集合 `Negotiation::make`。

**数据结构 = Table 树（让步层级）**：每个表代表一个提交排列 `[已让步者…, 本表提交者]`——"我承诺兼容序列中所有前面的 proposal"。submit 版本必须严格递增（modular），旧提交的子树级联作废（defunct 标志实时同步）。

**三原语**：
- `submit(itinerary)`——在父链约束下重搜出的无冲突新计划
- `reject(alternatives)`——拒绝 + 附上 rollout 备选菜单（"我可以这样绕/等"）供对方重规划
- `forfeit(blockers)`——弃权 + 归因阻塞者集合

**搜索在参与方本地**（SimpleNegotiator）：
1. NegotiatingRouteValidator（父链 proposal + 备选 rollout + 局外者行程三合一）塞进时空 A* 重搜
2. 被挡 → 换对手备选组合再试（绕行）；被父表提交者挡 → mask 掉他 + Rollout 展开己方备选 → reject 递菜单
3. 全败 → forfeit

**仲裁（Evaluator）**：胜出判据 = `Σ(各车完成时间 − 各车最优完成时间)` 最小（QuickestFinish）——弃权代价折算成完成时间惩罚，不是显式出价。**结算**：胜表各 Submission 经 ApprovalCallback 回写 `participant.set()` 落库成为新事实。

### 3.3 fleet adapter 的实战接线

- **双轨规划兜底**：`SearchForPath` 同时跑 greedy（无交管）+ compliant（带 validator，成本上限 = greedy×3）；compliant 被 block 超限时**直接提交 greedy 计划制造冲突开口**，逼谈判发生——系统不会因等待谈判而卡死
- **谈判超时**：`2s + 轮次×10s` interrupt；轮次越深 leeway 越大（越愿意接受贵的解）
- **任务分配不看交通**：Estimate 用零交通流最优时间（可缓存），冲突处理全部推迟到执行端
- traffic_light 模式 = blockade（机器人自己寻路，RMF 只裁走/停）+ schedule 谈判双轨

---

## 四、我们的现状对照

| 维度 | RMF | robot-scheduler 现状 | 差距/评价 |
|---|---|---|---|
| 资源模型 | 连续时空几何（轨迹+凸形+时间桶） | 离散点+段（三层：活动租约/驻留点/驻留段） | 离散化简化，符合目标点范式，保留 |
| 路线占用 | ReservedRange 随走随放 | **全路线途经点一次性持有** | 主要升级点（P2） |
| 停车点约束 | can_hold 驱动冲突区扩张 | 无（靠点位类型+驻留阈值 0.8m） | P2 引入 can_hold 语义 |
| 授予粒度 | 逐 checkpoint 授权 | 一次性授权全路线 | P2 |
| 冲突消解 | 谈判三招（hold/绕行/reject 菜单） | 排队等待 + 段前复检；**不改道不让路** | P3 |
| 死锁 | 编译期环约束预防 + release 让路 | DeadlockMonitor **只告警** | P2 先加让路，P3 考环约束 |
| 时间维度 | 时空轨迹时间窗共享 | 无（ETA 仅估算） | 目标点范式下不引入，用 ETA 近似 |
| 任务分配解耦 | 分配零交通流估计 ✓ | assigner 用 ETA+电量（不含冲突项）✓ | 已对齐 |
| 失败防护 | critical_error / Rectifier 对账 | stop 确认幂等 + 驻留原子切换 ✓ | 已对齐（各自实现） |

**已对齐 RMF 思想的部分**：占用即互斥的保守安全侧、到位原子驻留切换（消灭幽灵窗口）、任务分配与交管解耦、事件驱动重算（段前复检≈ready 重扫的简化版）。

---

## 五、升级设计：交管独立模块（Traffic Authority）

### 5.1 模块边界（P1：先立骨架，不改行为）

```
robot-scheduler-core
└── traffic/                      ← 独立交管包，对外唯一门面
    ├── TrafficAuthority          ← 新门面接口（合并现 TrafficRegistry + ActiveRouteRegistry 职责）
    │     reserveRoute(taskId, robot, waypoints[])      // 活动租约（P1 全量；P2 变 range）
    │     advance(robot, waypointReached)               // P2：随走随放（对齐 reached）
    │     yield(robot, retreatWaypoint)                 // P2：让路（对齐 release）
    │     dwell(robot, waypoint | segment)              // 驻留登记
    │     release(robot)                                // 全量释放
    │     awaitClear(taskId, robot, blockers→...)       // 等待 + 复检（现 awaitSegmentEntryClear 上移）
    ├── Lease / ReservedRange     ← 领域模型（P1: 全路线租约；P2: {begin,end} range）
    ├── HoldPolicy                ← can_hold 语义：点位可停性（workstation/parking=true，窄道=false）
    ├── DeadlockDetector          ← 现 DeadlockMonitor 移入，P2 起输出让路建议而非仅告警
    └── InMemoryTrafficAuthority  ← 实现（现 InMemoryTrafficRegistry 演进）
```

原则：`TaskExecutionEngine` 只调 `TrafficAuthority` 门面，不再直接碰注册表内部；traffic 包不依赖 task/fleet（依赖倒置，可独立单测、独立演进）。

### 5.2 P2：checkpoint 化分段租约（blockade 模式移植）

1. `reserveRoute` 从"全量点"改为 `ReservedRange{begin=0, end=k}`——初始只授到第一个冲突区前的可停点
2. 引擎逐段 navigate 成功后调 `advance(robot, n)` → `begin=n`（身后释放）→ 触发他人授予重算
3. `HoldPolicy`：地图点位带 `can_hold`（path 类型中间点=false），授予收缩时落到最近可停点
4. 死锁：DeadlockDetector 升级为占用图环检测 + **让路建议**（选牺牲者退到 standby——现 navigateToStandby 机制复用）
5. 兼容期：配置开关逐灰度（先双跑对比，后切 range 模式）——**交管行为开关，不是业务动作开关**

### 5.3 P3：冲突消解三招（schedule 谈判思想的简化移植）

不引入完整 Negotiation（我们没有时间维度，谈判退化为**排序问题**）：

1. **等待**（已有）：TRAFFIC_WAIT 排队
2. **改道**：规划器带占用图重搜（A* 把被占途经点当临时障碍，代价上限 = 原路 ×1.5，超限放弃改道）
3. **让路**：冲突方中被判低优先级（任务可打断/离冲突远）者收到让路指令 → 移动到最近 standby 点 → 释放占用
4. 仲裁：QuickestFinish 简化版——比较"等待方案总完成时间 vs 让路方案总完成时间"取小

### 5.4 不移植的部分（明确决策）

- 连续时空几何/Timeline/FCL——目标点范式无轨迹可入库，离散点段模型保留
- 谈判桌 Table 树 + rollout 备选菜单——规模不到（≤20 台），排序式消解够用
- 分布式 Writer/Rectifier/版本缺口回放——单进程部署，无此需求
- 朝向约束/差速模型——导航归机器人本体

---

## 六、源码位置索引

| 子系统 | 路径（相对 open-rmf/src/open-rmf/rmf/） |
|---|---|
| blockade 协议 | rmf_traffic/rmf_traffic/{include,src}/rmf_traffic/blockade/ |
| schedule 预约表 | 同上 .../schedule/（Database.cpp 为核心） |
| 谈判状态机 | .../schedule/Negotiation.cpp + agv/SimpleNegotiator.cpp |
| 时空 A* | .../agv/planning/DifferentialDrivePlanner.cpp（ScheduledDifferentialDriveExpander） |
| 备选展开 | .../agv/Rollout.cpp |
| adapter 接线 | rmf_ros2/rmf_fleet_adapter/src/{events/GoToPlace.cpp, services/Negotiate, jobs/SearchForPath.cpp} |
| 三种模式 | .../src/{full_control, read_only}/ + agv/EasyTrafficLight.cpp |
