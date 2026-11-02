-- 修复：V3 曾以不同内容应用过（开发期热改脚本），重置校验和以便继续演进
UPDATE flyway_schema_history SET checksum = -1075128540 WHERE version = '3';
