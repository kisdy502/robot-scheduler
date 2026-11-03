# 多实例部署预留（方案 §3.3 / §8.2）
# 本期仍为单活：使用权、外部作业、交通占用及命令序号已持久化（runtime-recovery.md），控制互斥仍在单进程。
# 数据库持久化只解决恢复，不等于支持多个实例同时发车；以下多实例方案仍为后续演进。
# 多实例演进路径（不改变现有接口）：
#
# 1. 快照镜像写 Redis（api 层任意副本可读）：
#    robot:scheduler:fleet_state:{fleet}   Hash robot_code -> RobotSnapshot JSON   TTL 15s
#    robot:scheduler:task_state:{id}       String TaskStateEvent JSON             TTL 10s
#    robot:scheduler:charger:lock          Redisson RLock（allocate/release 原子）
#    robot:scheduler:assign:lock           RLock（分配触发互斥）
#
# 2. 内核单主：robot:scheduler:leader 租约（参考 qc-robot RedisLeaseLockService 模式），
#    TaskExecutionEngine/FleetManager 仅主实例运行，副本只服务查询接口。
#
# 3. TrafficRegistry Redis 化：段占用 SETNX + 租约（TTL 30s 自动释放），接口已按字符串资源 ID 设计，无需改动。
