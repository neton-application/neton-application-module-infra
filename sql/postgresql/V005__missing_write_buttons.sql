-- V005：补上「页面开着、增删改却授不出去」的按钮权限
--
-- ── 缺陷 ──
-- baseline 给邮件与短信的四个页面（邮箱账号 / 邮件模板 / 短信渠道 / 短信模板）种了页面菜单，
-- 却没种对应的 create / update / delete 按钮行。后果不是「按钮灰着」那么轻：
-- RBAC spec §6.1 里权限集是从**菜单行**收集的，没有菜单行就没有权限串，于是
--   1) 前端 usePermission 判定为假 → 按钮不渲染；
--   2) 就算直接调接口，@Permission 也会 403。
-- 也就是说除了 super_admin，**没有任何角色能新增一个邮箱账号** —— 而邮件通道正是找回密码的依赖。
--
-- ── 为什么落在 infra ──
-- DB-MIG-7A：module-system 不持有迁移，它的 SQL 由 module-infra 合并持有。这些是 system:* 的按钮，
-- 归属仍是 system 域，所以写在 infra 的迁移里，与 baseline 中那些菜单种子同处一地。
--
-- 按钮行的形状与 baseline 中的三级行一致：type=3、path 空、component NULL、status=1
-- （status 列默认 0，漏写这一位按钮既不进权限集也不报错，见 V006 的同类注释）。
INSERT INTO system_menus (name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at)
SELECT v.name, v.permission, 3,
       (SELECT p.id FROM system_menus p WHERE p.permission = v.parent_permission AND p.type = 2),
       '', NULL, '', v.sort, 1,
       (extract(epoch from now()) * 1000)::bigint, (extract(epoch from now()) * 1000)::bigint
  FROM (VALUES
    ('邮箱账号新增', 'system:mail-account:create',   'system:mail-account:query',   1),
    ('邮箱账号修改', 'system:mail-account:update',   'system:mail-account:query',   2),
    ('邮箱账号删除', 'system:mail-account:delete',   'system:mail-account:query',   3),
    ('邮件模板新增', 'system:mail-template:create',  'system:mail-template:query',  1),
    ('邮件模板修改', 'system:mail-template:update',  'system:mail-template:query',  2),
    ('邮件模板删除', 'system:mail-template:delete',  'system:mail-template:query',  3),
    ('短信渠道新增', 'system:sms-channel:create',    'system:sms-channel:query',    1),
    ('短信渠道修改', 'system:sms-channel:update',    'system:sms-channel:query',    2),
    ('短信渠道删除', 'system:sms-channel:delete',    'system:sms-channel:query',    3),
    ('短信模板新增', 'system:sms-template:create',   'system:sms-template:query',   1),
    ('短信模板修改', 'system:sms-template:update',   'system:sms-template:query',   2),
    ('短信模板删除', 'system:sms-template:delete',   'system:sms-template:query',   3)
  ) AS v(name, permission, parent_permission, sort)
 WHERE EXISTS (SELECT 1 FROM system_menus p WHERE p.permission = v.parent_permission AND p.type = 2)
   AND NOT EXISTS (SELECT 1 FROM system_menus m WHERE m.permission = v.permission);
