-- compatible_fleets 改为 text（逗号分隔）：MyBatis String 映射无法写 PG array 字面量（malformed array literal）
ALTER TABLE t_charger ALTER COLUMN compatible_fleets TYPE text USING NULL;
