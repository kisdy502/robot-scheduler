-- 一次交通事件可包含整条路线的多个资源 ID，逗号拼接后不受单个 ID 长度限制。
-- 保留原字段名、历史数据及对外字符串格式，只扩大存储容量。
ALTER TABLE t_traffic_event ALTER COLUMN resource_id TYPE TEXT;
