# RMS 与本体集成手册（契约 · 坑 · 到位确认）

Base URL：http://127.0.0.1:18083（容器 robot-management-service-local）｜ OpenAPI：/v3/api-docs（字段命名与实际契约不完全一致，见下）

## 一、路径前缀契约（最高频踩坑）

| 端点类 | 前缀 | 例 |
|---|---|---|
| **RMF 命令类**（fleet status/inventory、navigate、start-activity、stop、completions/ack） | `/api/v1/robot-management/**/rmf/**` | `/api/v1/robot-management/rmf/robots/{code}/start-activity` |
| 管理类（机器人 CRUD、模板推送） | `/api/v1/robot-management/**`（无 /rmf） | `/api/v1/robot-management/robots/body/task-template/add` |

打错前缀的表象：Spring 落到静态资源处理器 → HTTP 200 信封里 `500 服务器内部错误` / `No static resource`（调度日志看到的是 RMS 报文，别在调度里找错）。

## 二、机器人分组（入队）

fleet status/inventory 只返回 `group_no` 匹配 fleetName（RG_20260407_00001）的机器人；新注册机器人无分组则调度不可见。
```
.\scripts\assign-group.ps1 -Codes RB_xxx [RB_yyy ...]
```
坑：PUT 是 **snake_case 全量更新**（OpenAPI 标 camelCase 是错的）；robot_name/status 必填，字段不回填会被清空；PS 5.1 中文需 `Invoke-WebRequest RawContentStream` 手动 UTF8 解码、发 body 用 `curl.exe --data-binary @file`。

## 三、命令契约

### navigate
- body：`{cmd_id, map_name, destination{x,y,yaw}, speed_limit, description}`
- **description 模板剥离（真机强校验）**：真机 agv-bridge 对 `description.task_template_code` 强校验，模板未下发会**拒绝 navigate**（报"任务模板不存在"）→ 调度 navigate 描述只带对账字段：rmf_task_id / task_id / taskflow_id / nav_target_point / end_point / destination
- 回执走信箱（completed_commands），调度按 rmf_command_uid（mock 回显 `sched:` 前缀）或 cmd_id（真机生成自有 uid，不回显前缀）匹配认领并 ack

### start-activity（上层应用职责，调度不下发）
- **充电契约**：`activity` 字段直接放**模板 code**（如 back-charge），机器人按模板 code 查模板；质检放固定类型名 quality_inspection
- 模板需先经 `POST /api/v1/robot-management/robots/body/task-template/add` 下发到本体（**RMS 校验 template_file_URL 必填**，需真实模板文件——当前流程模板人工预置到机器人容器）
- description 需带 task_id（body_task_id 回执关联键）+ task_template_type + task_template_code + end_point + destination + taskflow_id + nav_target_point

### stop
- `POST /rmf/robots/{code}/stop`，body `{running_cmd_id, stop_cmd_id}`；**不要带 body_task_id**（未知 ID 返回 10000）
- 幂等成功：`NO_ACTIVE_TASK` 或 `BODY_STOP_TASK_REJECTED + reject_reason 含 "already"`（本体任务已终态）——两类都必须归一成功，否则死循环

### 回执信箱（completed_commands）
- RMS 持续投递直到 ack；上层读走结果后应 ack 清箱
- **兜底机制**：调度 ExternalActivityMirror 将非调度下发的外部回执存档（t_external_activity，含 failure_msg）后**代为 ack**——上层中止/离场也不积压
- 上层读结果的正确姿势：轮询调度 `/api/v1/observability/activities?task_id=`（持久镜像），不要轮询 RMS 信箱（会被立刻清掉）

### 本体忙拒绝
- `BODY_START_TASK_REJECTED` / `BODY_ADD_TASK_REJECTED` → 调度识别为忙：stop(null) 清除后重试一次

## 四、地图一致性（三方对账）

- **本体 → RMS**：状态上报 map_info.current_map_name（如 map1111SK）；RMS RMF 层只认 L1 时打 WARN fallback
- **调度侧校验（已上线）**：每 30s 拉管理接口 `current_map_code` 与激活地图名严格比对，不一致 → **禁止派发** + MAP_MISMATCH 事件 + 失败原因带两侧地图名
- **行为校验兜底**：到达回执后实际位置与目标偏差 > 3m 判"地图疑似不一致"，任务失败止损 + MAP_SUSPECT 事件
- 换图标准操作：换调度 local-map → `TRUNCATE t_robot_runtime` → 重启调度 → 机器人侧同步加载同图

## 五、导航实际到点确认

导航成功 = 命令回执成功 **且** 实际位置核验通过，不能单凭 RMS COMPLETED：
- 分段目标位置误差 ≤ **0.20m**；回执先到而位置未跟上 → "等待实际到点确认"（最多 ~10s，期间保留交通资源）
- 只采信在线、坐标有效、下发后收到、调度接收时间 5s 内的状态帧
- 超时失败：请求停车进入占用保留流程；失败信息带目标/实际/偏差
- RMS 协议无原始采样时间/帧序号——不能识别上游旧位置重包装，需 RMS/本体提供进一步核验

## 六、历史联调坑速查

| 坑 | 说明 |
|---|---|
| rms_task 重复 task_id | 分段 navigate 透传同一 task_id → RMS 历史表 insert 冲突（仅 WARN 不影响执行）；候选方案未定 |
| 带标记段的充电行为不稳定 | 本体对带 charge 标记的 navigate 触发时机不定（到点充/原地充）→ 已全部去除标记，充电=纯导航+上层显式 start-activity |
| mock vs 真机差异 | mock 回显 sched: uid、不校验模板；真机生成自有 uid、强校验模板、stop 幂等语义完整——联调必须过真机 |
| PS 5.1 | .ps1 中文必须 UTF-8 带 BOM；Invoke-RestMethod PUT byte[] RMS 解析不出（用 curl.exe --data-binary @file） |
