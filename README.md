# robot-scheduler

机器人调度服务（Java 重构版）—— 单服务替代 `robot-scheduler-rest-api + rmf-qc-fleet-adapter + RMF Core`。

## 核心职责

- 对上层（taskflow）提供与旧 REST API 0.2.0 字段一致的 17 个调度端点（envelope `{status,msg,data,timestamp}`）
- 简化调度内核：拓扑图（nav_graph）、路径规划（JGraphT）、lane/waypoint 资源占用与互斥组、集中任务分配（BatteryAware 代价）、任务执行状态机、充电桩池
- 南向调用 RMS（robot-management-service）8 端点：状态/清单轮询 + navigate/start-activity/stop/pause/resume/ack

## 上下游依赖

| 方向 | 系统 | 协议 |
|---|---|---|
| 上游 | taskflow-management-service | HTTP（本文档 17 端点） |
| 下游 | robot-management-service (RMS) | HTTP（`robot.scheduler.rms.base-url`） |
| 基础设施 | PostgreSQL 16 / Redis 7 / MinIO(S3) | — |

## 模块结构

见 [docs/architecture.md](docs/architecture.md)（模块职责 + 调度算法详解 + 扩展指南）。

## 关键文档

| 文档 | 内容 |
|---|---|
| [docs/architecture.md](docs/architecture.md) | 架构总览：六模块 + core 八包算法详解 + 扩展指南 |
| [docs/conflict-scenarios.md](docs/conflict-scenarios.md) | 12 类交通冲突场景矩阵（场景/算法/代码位置/错误提示） |
| [docs/api-test-guide.md](docs/api-test-guide.md) | HTTP 接口测试手册 + 多机仿真机器人接入 |
| [docs/multi-instance-roadmap.md](docs/multi-instance-roadmap.md) | 多实例部署演进方案 |

## 关键接口

`/swagger-ui.html`；任务下发 `POST /api/v1/scheduler/dispatch_task`，状态查询 `GET /api/v1/scheduler/tasks/status?scheduler_request_id=`，
全量快照 `GET /api/v1/runtime/fleets/snapshot`。

## 关键表

`t_task_record`（调度记录）/ `t_task_state`（任务状态镜像）/ `t_charger`（充电桩）/ `t_scene_resource_bundle` / `t_robot_home` / `t_point` / `t_traffic_event`。

## 运行与调试

```bash
# 方式一：docker compose 一键起全套（PG16 + Redis7 + 服务）
docker compose -f local-dev/docker-compose.yml up -d --build

# 方式二：本地依赖 + 直跑
docker run -d -p 55432:5432 -e POSTGRES_DB=scheduler_service -e POSTGRES_USER=scheduler -e POSTGRES_PASSWORD=scheduler postgres:16
docker run -d -p 6379:6379 redis:7

./mvnw -DskipTests package
MAP_METADATA_PATH=./local-map/map_info.json DB_HOST=127.0.0.1 DB_PORT=55432 REDIS_HOST=127.0.0.1 \
  java -jar robot-scheduler-boot/target/robot-scheduler-boot-0.1.0-SNAPSHOT-exec.jar
```

- 地图：启动兜底读 `MAP_METADATA_PATH`；生产走 `POST /api/v1/maps/upload`（multipart: metadata.json + png + yaml）
- 充电桩：地图 charge 点位自动对账入库（t_charger），占用状态持久化
- 任务记录 GC：published 无受理 1800s → 放弃归档；7200s 未更新 → 归档

## 常见问题

- jsonb 写入报类型错误：确认 datasource url 带 `stringtype=unspecified`
- 接口字段兼容问题：对照旧栈 `robot-scheduler-rest-api/src/robot_scheduler_rest_api/test/test_rest_api.py` 断言
- 多实例部署：见 `docs/multi-instance-roadmap.md`（快照镜像 Redis key 设计 + 内核单主方案）

