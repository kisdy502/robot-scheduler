# robot-scheduler

机器人调度服务（Java 重构版）—— 单服务替代 `robot-scheduler-rest-api + rmf-qc-fleet-adapter + RMF Core`。

## 核心职责（新版架构 2026-11）

**调度只管移动**：路径规划 → 导航到点 → 交通管制/点位占用 → 完成。质检、充电启动等业务动作（start-activity）由上层应用直连 RMS 下发，调度做观测镜像不编排。

- 移动统一入口 `POST /api/v1/scheduler/goto-place`（dispatch_task 已废弃保留兼容）
- 业务流闭环：assign-task 抢占 → goto-place 移动 → 上层直连 RMS 动作 → observability/activities 读结果 → release-robot 释放
- 任务 ID 契约：taskflow_id=雪花整数；task_id=taskflow_id+yyyyMMddHHmmssSSS
- 南向 RMS：RMF 命令端点（/rmf/ 前缀）状态轮询 + navigate/stop/ack；**调度不发 start-activity**

## 上下游依赖

| 方向 | 系统 | 协议 |
|---|---|---|
| 上游 | taskflow-management-service（或前端业务模拟器） | HTTP |
| 下游 | robot-management-service (RMS) | HTTP（`robot.scheduler.rms.base-url`） |
| 基础设施 | PostgreSQL 16 / Redis 7（预留）/ MinIO(S3) | — |

## 文档索引

| 文档 | 内容 |
|---|---|
| [docs/architecture.md](docs/architecture.md) | **架构总览**：模块 + 内核算法 + 新版"只管移动"定调 + 观测体系 |
| [docs/traffic-control.md](docs/traffic-control.md) | **交通管制**：三级占用模型 + 12 冲突场景矩阵 + 停止确认/异常恢复 |
| [docs/rms-integration.md](docs/rms-integration.md) | **RMS 集成**：路径前缀契约 + 命令契约 + 模板/地图一致性坑 + 到位确认 |
| [docs/api-guide.md](docs/api-guide.md) | **API 测试手册**：业务流五步 + 观测接口 + 模拟器 + 压测脚本 |
| [docs/acceptance-and-recovery.md](docs/acceptance-and-recovery.md) | **验收与恢复**：分阶段验收门槛 + 持久化正本 + 重启行为 |
| [docs/storage-design.md](docs/storage-design.md) | 存储三层设计 + 全部表（V1-V10） |
| [docs/rmf-traffic-analysis.md](docs/rmf-traffic-analysis.md) | RMF 交管源码考古 + 升级路线（参考库） |
| [docs/multi-instance-roadmap.md](docs/multi-instance-roadmap.md) | 多实例部署演进方案 |
| [docs/unit-test-review.md](docs/unit-test-review.md) | 测试阅读与报告工具 |
| [docs/history/](docs/history/) | 历史归档（旧接入模式/早期分析/交通等待快照） |

## 关键接口

`/swagger-ui.html`；移动 `POST /api/v1/scheduler/goto-place`，状态 `GET /api/v1/scheduler/tasks/{task_id}/status`，
全量快照 `GET /api/v1/runtime/fleets/snapshot`，业务动作镜像 `GET /api/v1/observability/activities?task_id=`。

## 关键表

`t_task_record` / `t_task_state`（移动任务）/ `t_external_activity`（外部动作镜像）/ `t_robot_event`（事件含占用时间线）/ `t_robot_runtime`（运行正本）/ `t_charger` / `t_map_metadata` / `t_traffic_event`。

## 运行与调试

```bash
# 方式一：docker compose 一键起全套
docker compose -f local-dev/docker-compose.yml up -d --build

# 方式二：本地依赖 + 直跑
./mvnw -DskipTests package
MAP_METADATA_PATH=./local-map/map_info.json java -jar robot-scheduler-boot/target/*-exec.jar
```

- 地图：启动兜底读 `MAP_METADATA_PATH`；生产走 `POST /api/v1/maps/upload`
- **换图**：换 local-map 后 `TRUNCATE t_robot_runtime` 再重启（否则恢复占用版本不一致拒启）
- 任务 GC：published 无受理 1800s 放弃；7200s 未更新归档

## 常见问题

- jsonb 写入类型错误：datasource url 带 `stringtype=unspecified`
- 接口字段兼容：对照旧栈 `test_rest_api.py` 断言
- RMS 报 500 "No static resource"：命令端点少了 `/rmf/` 前缀（见 rms-integration.md）
- 机器人不可派"停止确认中"：stop 幂等确认未完成，10s 内自愈；不自愈查 RMS stop 回绝原因
