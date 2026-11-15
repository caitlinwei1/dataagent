"""Write cases.jsonl: legit analytic SQL that must pass, and unsafe / wrong SQL that must be blocked.

expect = pass | READ_ONLY | WHITELIST | COLUMN | SYNTAX. dialect is informational (mysql | pg).
"""
import json, os

P = "pass"
cases = [
    # ---------------------------------------------------------------- legit (must pass)
    ("L01", "mysql", P, "SELECT SUM(pay_amount) AS gmv FROM trd_order WHERE paid_at >= '2026-02-01' AND paid_at < '2026-05-01'"),
    ("L02", "mysql", P, "SELECT s.name, SUM(o.pay_amount) gmv FROM trd_order o JOIN dim_shop s ON o.shop_id = s.id GROUP BY s.name ORDER BY gmv DESC LIMIT 10"),
    ("L03", "mysql", P, "SELECT DATE_FORMAT(o.paid_at, '%Y-%m') AS ym, COUNT(*) AS orders, SUM(o.pay_amount)/COUNT(DISTINCT o.member_id) AS arpu FROM trd_order o WHERE o.order_status <> 'CANCELLED' GROUP BY ym ORDER BY ym"),
    ("L04", "mysql", P, "SELECT r.big_region, SUM(i.pay_share) FROM trd_order_item i JOIN trd_order o ON i.order_id = o.id JOIN dim_shop s ON o.shop_id = s.id JOIN dim_region r ON s.region_id = r.id GROUP BY r.big_region"),
    ("L05", "mysql", P, "WITH m AS (SELECT shop_id, SUM(pay_amount) gmv FROM trd_order GROUP BY shop_id) SELECT shop_id, gmv, RANK() OVER (ORDER BY gmv DESC) rk FROM m"),
    ("L06", "mysql", P, "SELECT o.shop_id, COUNT(DISTINCT rf.order_id) / COUNT(DISTINCT o.id) AS refund_rate FROM trd_order o LEFT JOIN trd_refund rf ON rf.order_id = o.id GROUP BY o.shop_id HAVING COUNT(DISTINCT o.id) > 100"),
    ("L07", "mysql", P, "SELECT sku_id, SUM(quantity) qty FROM trd_order_item WHERE created_time >= NOW() - INTERVAL 30 DAY GROUP BY sku_id ORDER BY qty DESC LIMIT 20"),
    ("L08", "mysql", P, "SELECT CASE WHEN pay_amount >= 500 THEN '高' WHEN pay_amount >= 100 THEN '中' ELSE '低' END AS band, COUNT(*) FROM trd_order GROUP BY band"),
    ("L09", "mysql", P, "SELECT member_id FROM trd_order WHERE member_id IN (SELECT id FROM usr_member WHERE level_id >= 3) LIMIT 100"),
    ("L10", "mysql", P, "SELECT 'paid' AS k, COUNT(*) FROM trd_order WHERE paid_at IS NOT NULL UNION ALL SELECT 'refund', COUNT(*) FROM trd_refund"),
    ("L11", "mysql", P, "SELECT w.name, SUM(inv.on_hand_qty) FROM wms_inventory inv JOIN wms_warehouse w ON inv.warehouse_id = w.id GROUP BY w.name"),
    ("L12", "mysql", P, "SELECT COALESCE(SUM(refund_amount), 0) FROM trd_refund WHERE refund_status = 'SUCCESS'"),
    ("L13", "mysql", P, "SELECT `o`.`shop_id`, COUNT(*) FROM `trd_order` `o` GROUP BY `o`.`shop_id`"),
    ("L14", "mysql", P, "SELECT gbi_ecommerce.trd_order.shop_id, COUNT(*) FROM gbi_ecommerce.trd_order GROUP BY gbi_ecommerce.trd_order.shop_id"),
    ("L15", "mysql", P, "SELECT d.year_month_str, SUM(o.pay_amount) FROM trd_order o JOIN (SELECT DISTINCT DATE_FORMAT(paid_at,'%Y-%m') year_month_str, id FROM trd_order) d ON d.id = o.id GROUP BY d.year_month_str"),
    ("L16", "mysql", P, "SELECT carrier_id, AVG(TIMESTAMPDIFF(HOUR, shipped_at, delivered_at)) AS hours FROM tms_shipment WHERE delivered_at IS NOT NULL GROUP BY carrier_id"),
    ("L17", "mysql", P, "SELECT COUNT(*) FROM trd_order WHERE YEAR(paid_at) = 2026 AND MONTH(paid_at) BETWEEN 2 AND 4;"),
    ("L18", "pg", P, "SELECT date_trunc('month', paid_at)::date AS m, SUM(pay_amount) FROM trd_order GROUP BY 1 ORDER BY 1"),
    ("L19", "pg", P, "SELECT shop_id, pay_amount, LAG(pay_amount) OVER (PARTITION BY shop_id ORDER BY paid_at) AS prev FROM trd_order LIMIT 50"),
    ("L20", "pg", P, "SELECT s.name, PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY o.pay_amount) FROM trd_order o JOIN dim_shop s ON s.id = o.shop_id GROUP BY s.name"),
    ("L21", "pg", P, "SELECT o.id FROM trd_order o WHERE o.paid_at >= CURRENT_DATE - INTERVAL '30 days' LIMIT 10"),
    ("L22", "pg", P, "WITH a AS (SELECT member_id, MIN(paid_at) first_paid FROM trd_order GROUP BY member_id), b AS (SELECT member_id FROM a WHERE first_paid >= '2026-02-01') SELECT COUNT(*) FROM b"),
    ("L23", "pg", P, "SELECT so.warehouse_id, COUNT(*) FROM wms_stockout_record so WHERE so.created_time BETWEEN '2026-10-12' AND '2026-10-18' GROUP BY so.warehouse_id"),
    ("L24", "pg", P, "SELECT COUNT(*) FILTER (WHERE pay_amount > 100) AS big, COUNT(*) AS total FROM trd_order"),
    ("L25", "mysql", P, "SELECT p.name AS platform, COUNT(*) FROM trd_order o JOIN dim_shop s ON o.shop_id = s.id JOIN dim_platform p ON s.platform_id = p.id WHERE p.name = '抖音' GROUP BY p.name"),
    ("L26", "mysql", P, "SELECT x.shop_id, x.gmv FROM (SELECT shop_id, SUM(pay_amount) AS gmv FROM trd_order GROUP BY shop_id) x WHERE x.gmv > 10000"),
    ("L27", "mysql", P, "SELECT ROUND(SUM(i.pay_share - i.cost_price * i.quantity), 2) AS gross_profit FROM trd_order_item i"),
    ("L28", "mysql", P, "SELECT COUNT(*) AS cnt FROM trd_order ORDER BY cnt"),
    ("L29", "pg", P, "SELECT EXTRACT(DOW FROM paid_at) AS dow, COUNT(*) FROM trd_order GROUP BY dow"),
    ("L30", "mysql", P, "SELECT IFNULL(c.name, '未知'), COUNT(*) FROM trd_order_item i LEFT JOIN prd_category c ON c.id = i.category_id GROUP BY c.name"),
    # ---------------------------------------------------------------- writes / DDL / side effects
    ("W01", "mysql", "READ_ONLY", "DELETE FROM trd_order WHERE id = 1"),
    ("W02", "mysql", "READ_ONLY", "UPDATE trd_order SET pay_amount = 0"),
    ("W03", "mysql", "READ_ONLY", "INSERT INTO trd_order (id) VALUES (1)"),
    ("W04", "mysql", "READ_ONLY", "DROP TABLE trd_order"),
    ("W05", "mysql", "READ_ONLY", "TRUNCATE TABLE trd_refund"),
    ("W06", "mysql", "READ_ONLY", "ALTER TABLE trd_order ADD COLUMN x INT"),
    ("W07", "mysql", "READ_ONLY", "CREATE TABLE t AS SELECT * FROM trd_order"),
    ("W08", "mysql", "READ_ONLY", "SELECT 1; DROP TABLE trd_order"),
    ("W09", "mysql", "READ_ONLY", "SELECT COUNT(*) FROM trd_order;\n-- 汇总\nDELETE FROM trd_order"),
    ("W10", "mysql", "READ_ONLY", "SELECT * FROM trd_order INTO OUTFILE '/tmp/o.csv'"),
    ("W11", "mysql", "READ_ONLY", "SELECT id INTO @x FROM trd_order LIMIT 1"),
    ("W12", "mysql", "READ_ONLY", "SELECT * FROM trd_order WHERE id = 1 FOR UPDATE"),
    ("W13", "mysql", "READ_ONLY", "SELECT * FROM trd_order WHERE id = 1 LOCK IN SHARE MODE"),
    ("W14", "mysql", "READ_ONLY", "SELECT SLEEP(30)"),
    ("W15", "mysql", "READ_ONLY", "SELECT BENCHMARK(100000000, MD5('x'))"),
    ("W16", "mysql", "READ_ONLY", "SELECT id FROM trd_order WHERE id = 1 AND SLEEP(5) = 0"),
    ("W17", "mysql", "READ_ONLY", "SELECT LOAD_FILE('/etc/passwd')"),
    ("W18", "pg", "READ_ONLY", "SELECT pg_sleep(30)"),
    ("W19", "pg", "READ_ONLY", "SELECT pg_read_file('/etc/passwd')"),
    ("W20", "pg", "READ_ONLY", "WITH d AS (DELETE FROM trd_order WHERE id = 1 RETURNING id) SELECT * FROM d"),
    ("W21", "mysql", "READ_ONLY", "CALL refresh_stats()"),
    ("W22", "mysql", "READ_ONLY", "SET GLOBAL max_connections = 1"),
    ("W23", "mysql", "READ_ONLY", "GRANT ALL ON *.* TO 'gbi_reader'@'%'"),
    ("W24", "mysql", "READ_ONLY", "REPLACE INTO trd_order (id) VALUES (1)"),
    ("W25", "pg", "READ_ONLY", "SELECT set_config('statement_timeout', '0', false)"),
    ("W26", "pg", "READ_ONLY", "SELECT pg_terminate_backend(pid) FROM pg_stat_activity"),
    ("W27", "mysql", "READ_ONLY", "SHOW GRANTS"),
    ("W28", "pg", "READ_ONLY", "EXPLAIN ANALYZE DELETE FROM trd_order"),
    # ---------------------------------------------------------------- whitelist / system / cross-db
    ("T01", "mysql", "WHITELIST", "SELECT table_name FROM information_schema.tables"),
    ("T02", "mysql", "WHITELIST", "SELECT user, authentication_string FROM mysql.user"),
    ("T03", "mysql", "WHITELIST", "SELECT api_key FROM saa_data_agent.model_config"),
    ("T04", "pg", "WHITELIST", "SELECT usename, passwd FROM pg_catalog.pg_shadow"),
    ("T05", "mysql", "WHITELIST", "SELECT id FROM trd_order UNION SELECT id FROM saa_data_agent.agent"),
    ("T06", "mysql", "WHITELIST", "SELECT COUNT(*) FROM trd_orders"),
    ("T07", "mysql", "WHITELIST", "SELECT * FROM trd_order o WHERE EXISTS (SELECT 1 FROM gbi_ecommerce_partial.trd_order p WHERE p.id = o.id)"),
    ("T08", "mysql", "WHITELIST", "SELECT shop_id, SUM(amount) FROM sales_daily GROUP BY shop_id"),
    # ---------------------------------------------------------------- columns that do not exist
    ("C01", "mysql", "COLUMN", "SELECT SUM(o.paid_amount) FROM trd_order o"),
    ("C02", "mysql", "COLUMN", "SELECT s.shop_name, COUNT(*) FROM trd_order o JOIN dim_shop s ON o.shop_id = s.id GROUP BY s.shop_name"),
    ("C03", "mysql", "COLUMN", "SELECT SUM(gmv) FROM trd_order"),
    ("C04", "mysql", "COLUMN", "SELECT r.region_name FROM dim_region r"),
    ("C05", "pg", "COLUMN", "SELECT date_trunc('month', o.pay_time) FROM trd_order o"),
    # ---------------------------------------------------------------- syntax
    ("S01", "mysql", "SYNTAX", "SELEC id FROM trd_order"),
    ("S02", "mysql", "SYNTAX", "SELECT id, FROM trd_order"),
    ("S03", "mysql", "SYNTAX", "SELECT SUM(pay_amount FROM trd_order"),
    ("S04", "mysql", "SYNTAX", ""),
]
with open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "cases.jsonl"), "w") as f:
    for i, d, e, s in cases:
        f.write(json.dumps(dict(id=i, dialect=d, expect=e, sql=s), ensure_ascii=False) + "\n")
print(len(cases), "cases")
