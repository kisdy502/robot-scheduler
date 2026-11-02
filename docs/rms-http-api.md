# RMS HTTP API 联调笔记

Base URL（本机 Docker，容器 robot-management-service-local）：http://127.0.0.1:18083

OpenAPI：http://127.0.0.1:18083/v3/api-docs（注意：schema 字段命名与实际契约不完全一致，见"分组设置"的坑）

## 机器人查询

```
# 详情（含 robot_status 原始机体状态）
http://127.0.0.1:18083/api/v1/robot-management/robots/{id}

# 列表（keyword 匹配 robot_code/robot_name，分页参数 page_num/page_size）
http://127.0.0.1:18083/api/v1/robot-management/robots?page_num=1&page_size=10&keyword=RB_20261024_00012

# RMF fleet status / inventory（按 fleetName = group_no 过滤，调度 1Hz 轮询的就是这两个）
http://127.0.0.1:18083/api/v1/robot-management/rmf/fleets/RG_20260407_00001/status
http://127.0.0.1:18083/api/v1/robot-management/rmf/fleets/RG_20260407_00001/inventory
```

## 机器人分组设置（机器人入队）

fleet inventory/status 只返回 `group_no` 匹配 fleetName 的机器人；机器人刚注册时 `group_no` 为 null，
调度界面看不到。RMS 管理页面暂无分组入口，用脚本设置：

```
# 默认 RB_20261027_00015 → RG_20260407_00001
.\scripts\set-robot-group.ps1
.\scripts\set-robot-group.ps1 -RobotCode RB_xxx -GroupNo RG_xxx
```

脚本底层三步（curl 等价）：

```
# 1. keyword 定位机器人 id
GET /api/v1/robot-management/robots?page_num=1&page_size=50&keyword={robotCode}
    → data.list[robot_code==robotCode].id

# 2. 拉详情（PUT 全量更新，字段回填来源）
GET /api/v1/robot-management/robots/{id}

# 3. PUT 更新分组（body 见下）
PUT /api/v1/robot-management/robots/{id}
{
  "robot_name": "JY历飞雨",
  "robot_sn": "...",
  "ip": "...",
  "model": "...",
  "firmware_version": "...",
  "robot_type": "qc-robot",
  "robot_type_no": "qc_robot",
  "group_no": "RG_20260407_00001",
  "status": "enabled"
}
```

### 坑（重要）

- **PUT 实际契约是 snake_case**：OpenAPI 里 RobotUpdateRequest 标的是 camelCase（robotName/groupNo...），
  发 camelCase 会报 `status cannot be blank; robotName cannot be blank`（10001）
- **PUT 是全量更新**：robot_name/status 必填，其余字段不回填会被清空
- 响应信封 `{status, msg, data}`，status==0 成功；10001 参数校验失败
- PS 5.1 `Invoke-RestMethod` 会把无 charset 的 UTF-8 响应误解码为 Latin-1（中文名乱码），
  需走 `Invoke-WebRequest -UseBasicParsing` 的 `RawContentStream` 手动按 UTF-8 解码
- PS 5.1 `Invoke-RestMethod -Method Put` 发 byte[] body 时 RMS 解析不出任何字段，必须用
  `curl.exe --data-binary @file`
- `.ps1` 文件必须 UTF-8 **带 BOM**，否则 PS 5.1 按 GBK 解析，中文注释会吞换行破坏代码

## stop 命令契约（2026-10-28 实测补充）

- `POST /api/v1/robot-management/rmf/robots/{code}/stop`，body `{"running_cmd_id":"0","stop_cmd_id":"<seq>"}`
- **不要带 body_task_id**：带未知任务 ID 返回 `10000 请求错误`；不带则停止当前活动任务（返回 `body_task_status: stopped` + 被停任务明细）
- 无活动任务时返回 `NO_ACTIVE_TASK`（accepted=false）；HTTP 客户端仅对 stop 的明确 result_code=NO_ACTIVE_TASK 归一为幂等成功，其他拒绝保持三态映射。随后仍需状态帧确认位置稳定，才解除交通占用保留
- 用途：本体任务状态机卡死（任务完成未关闭/导航目标未上报完成导致在途任务永挂）时清除；调度引擎 busy 拒绝防御（BODY_START_TASK_REJECTED → stop → 重试）即依赖此契约

## 地图一致性协议提案（待 RMS/本体侧排期）

三方地图一致性问题的职责划分：
- **本体 → RMS**：机器人状态上报需携带 SLAM 地图标识（map_name + version/checksum，而非仅楼层名 L1）；RMS 与车队地图配置比对，不一致拒绝派单并回报错误
- **RMS → 调度**：inventory/status 的 robot.map_name 建议升级为完整地图标识（如 map1027S v3.2.8），调度与激活地图比对，不一致标记机器人不可派发
- **调度侧已上线的兜底（不依赖协议升级）**：到达回执后行为校验——机器人实际上报位置与目标点偏差 > 3m 即判定`地图疑似不一致`：任务立即失败止损（失败原因带目标/实际坐标与激活地图名）+ 机器人事件日志记 ERROR（event_type=MAP, reason=MAP_SUSPECT），前端事件中心可见

典型事故场景（2026-10-28）：仿真机器人 SLAM 加载 Map1025L，调度激活 Map1027S，两图同一物理世界但点位坐标错位 → 名字对坐标错 → 机器人乱走。行为校验可在第一段到达即暴露并止损。

## 三方地图一致性·调度层校验（2026-10-28 已上线）

数据源：机器人管理列表接口 `GET /api/v1/robot-management/robots` 的 `current_map_code`（机器人 SLAM 真实加载地图名，如 map1027S）。
- 调度每 30s 拉取 robotCode → current_map_code 映射，随状态帧写入机器人快照
- 与调度激活地图名严格比对：不一致 → 机器人**禁止派发** + 事件日志 ERROR（MAP/MAP_MISMATCH，带两侧地图名）+ 恢复时 INFO；未上报标识的机器人不判定（兼容旧协议）
- 任务失败原因精准化：`机器人地图与调度不一致（机器人: map1025L vs 调度: map1027S），已禁止派发`
- 仿真 mock 配置同步：`sim-robot-config.yml` 的 `maps.current_map_name` 必须与调度激活地图名一致
- 仍待 RMS 侧：车队地图配置 vs 机器人上报地图的校验与拒派（RMS 层职责）

## 本体任务拒绝码补充（2026-10-28 实测）

- `BODY_START_TASK_REJECTED`：中文文案`已有在途任务 xxx，请先 stop/pause`（新任务被拒）
- `BODY_ADD_TASK_REJECTED`：英文文案`task_id in flight (status=idle), stop first`（残留任务占注册表：取消时 stop 误带 body_task_id 被拒 10000 后未清理）
- 调度防御：两种均识别为忙拒绝 → stop(null) 清除 → 重试一次；取消链路 stop 一律不带 body_task_id

## 真机充电任务的本体行为契约（2026-10-28 实测，2026-10-30 二次修订）

- **定版流程（2026-10-30，对齐旧栈验证过的契约）**：充电任务 = **纯导航到充电位（全程不带 charge 模板标记）→ start-activity charge 显式充电**，与质检流程完全一致（仅模板类型不同）；mock 与真机同路径。任务发起时机器人已在桩上充电（is_charging=true 且在充电位上）→ 直接接管等待，不重复下发
- 旧栈佐证（2026-10-30 实测旧调度日志）：旧栈充电即直接 `start-activity charge`（可无导航），或单次 navigate 直达充电位（机器人自主路径规划，不分段）——证明本体接受 start-activity 充电
- 本体对带 `task_template_type=charge` 标记的 navigate 行为**不稳定且不校验点位**：12:17 到点才自充（P4）、14:19 收到即原地自充（H1，距 C1 6.4m 未导航）→ 调度侧导航段已全部去除标记，不再依赖本体自充
- 调度防御（保留）：检测到 `is_charging=true` 时校验机器人驻留吸附（0.5m）在 type=charge 点位上；不在 → stop 本体任务 + 任务快速失败；在 → 兜底接管等待充电结束（is_charging 变 false 或 1h 超时）
- mock 不自充（只认显式 start-activity），与真机路径统一
- **充电 start-activity 线上模板标识（2026-10-30 定稿）**：机器人侧按**模板 code**（服务器创建模板时生成/填写的唯一号，如 `auto-goto-charge101`）查模板，不是固定类型 `charge`——直接下发 `start-activity charge` 本体回 FAILED"动作模板不存在（charge 动作模板 charge 未下发）（通过 task_template/add 下发模板）"。**概念区分：`task_template_type=charge` 是固定类型（调度判类别用），`task_template_code` 是模板唯一号（下发给机器人执行用）**。调度实现：顶层 `task_template_code` 注入 params 透传到 description；HttpRobotManagementClient 下发 start-activity 时 activity 替换为 `description.task_template_code`，无 code 时用 `robot.scheduler.rms.charge-activity-name` 兜底（当前车队模板 back-charge，覆盖前端手动充电/内部低电量自动充电）；`description.task_template_type` 恒为 charge 不改写；内部语义/mock 不受影响。前端手动充电（MapView 右键充电点）已带 `task_template_code`（VITE_CHARGE_TEMPLATE_CODE 可配）。质检任务如遇同样报错可复用此模式
- **待本体侧修复**：自充前应校验当前点为充电位类型
- **本体充电触发行为不稳定（2026-10-30 14:19 实测，待查固件）**：带标记段的触发时机出现两种表现——12:17 为"导航到段目标 P4 后自充"（到点触发），14:19 为"收到最终段（H1→C1）任务后在 H1 原地自充，未导航到 C1"（收到即触发）。若固件确认为 start-activity 充电协议，调度可切换为"全段不带标记 + 到位后显式 start-activity"（与 mock 同路径）

## RMS rms_task 重复 task_id（2026-10-30 发现，暂不处理）

- 现象：分段导航的每段 navigate 都在 description 里透传上游 `task_id`（ctx-charge-...），RMS `RmsTaskRegistry` 以它为 `rms_task` 主键 insert → 第二段起每段报 `duplicate key violates rms_task_pkey`
- 影响：仅 RMS 历史落库失败（WARN），命令执行不受影响；但**疑似与上述 14:19 充电行为异常相关**（RMS 对重复 task_id 的任务复用/下发路径未知）
- 候选方案（待定）：调度侧每段唯一子任务 ID（`ctx-...#segN`）/ 中间段不带 task_id / RMS 侧改 upsert
