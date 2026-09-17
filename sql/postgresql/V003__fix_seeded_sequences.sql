-- V003：把 baseline 用显式 id 播种过的表的序列推到 MAX(id)
--
-- ── 缺陷 ──
-- V001 baseline 里有五张表是带 id 列插入的（`INSERT INTO system_users (id, ...) VALUES (1, ...)`），
-- 而 PostgreSQL 的 BIGSERIAL 序列只在**不指定 id** 时才前进。于是全新库里序列还停在初始值，
-- 第一次通过接口新增就撞主键。
--
-- ── 后果 ──
-- 不是「偶尔报错」，是全新部署的核心功能直接不可用：
--   - 管理台建第二个管理员账号 → 500 duplicate key "system_users_pkey"
--   - 新建角色 → 500；新增字典项 → 连撞 21 次才可能成功
-- 而且报的是 Internal Server Error，运维从信封上看不出是序列问题，只会以为「后台坏了」。
-- 之所以一直没被发现：种子库里只有 admin 一个用户、super_admin 一个角色，
-- 谁也没在全新库上走过「再建一个账号」这条路。
--
-- ── 为什么修在 infra 而不是各模块 ──
-- 这几张表的种子都在 infra 的 baseline 里（DB-MIG-7A：system 的 SQL 由 infra 合并持有），
-- 谁播的种谁负责把序列收尾。修在这里所有基于本脚手架的产品都受益，不是某个发行版的私事。
--
-- setval 的第三个参数省略即 is_called=true，下一次 nextval 返回 MAX(id)+1。
-- 对序列已经正确的库，这是把它设成当前最大值的空操作，可反复执行。
-- 表为空时 COALESCE 兜到 1，与 BIGSERIAL 的起始值一致。
SELECT setval(pg_get_serial_sequence('system_users', 'id'),      COALESCE((SELECT MAX(id) FROM system_users), 1));
SELECT setval(pg_get_serial_sequence('system_roles', 'id'),      COALESCE((SELECT MAX(id) FROM system_roles), 1));
SELECT setval(pg_get_serial_sequence('system_user_roles', 'id'), COALESCE((SELECT MAX(id) FROM system_user_roles), 1));
SELECT setval(pg_get_serial_sequence('system_dict_types', 'id'), COALESCE((SELECT MAX(id) FROM system_dict_types), 1));
SELECT setval(pg_get_serial_sequence('system_dict_data', 'id'),  COALESCE((SELECT MAX(id) FROM system_dict_data), 1));
-- 下面两张 baseline 里是不带 id 插入的（序列本来就对），一并收口，免得将来有人加显式 id 种子时漏掉
SELECT setval(pg_get_serial_sequence('system_menus', 'id'),      COALESCE((SELECT MAX(id) FROM system_menus), 1));
SELECT setval(pg_get_serial_sequence('system_role_menus', 'id'), COALESCE((SELECT MAX(id) FROM system_role_menus), 1));
