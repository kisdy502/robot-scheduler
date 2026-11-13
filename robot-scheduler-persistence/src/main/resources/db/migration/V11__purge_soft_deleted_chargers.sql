-- 充电桩表历史软删行物理清除：软删行与新图重名桩（如 C7）在地图对账 INSERT 时撞物理唯一键
-- uk_charger_charger_id，且逻辑删除机制下无法复活。对账语义已改为"停用代替删除"（V11 之后不再产生软删行）。
DELETE FROM t_charger WHERE is_deleted = true;
