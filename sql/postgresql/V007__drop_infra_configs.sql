-- 配置能力搬去 system（表与代码见 module-system V001__system_settings）。
-- 这里只做 infra 自己该收的尾：删掉自己的表、改掉自己插入的菜单行。
--
-- infra_configs 生产无有效数据（唯一使用方是尚未上线的签到周期配置，2026-08-07 确认），
-- 故不搬数据、不留过渡期双表 —— 空表加死代码的成本比删掉高，
-- 下一个人得先花时间确认它是不是还在用。
DROP TABLE IF EXISTS infra_configs;

-- 菜单留在 infra 的原因：system_menus 表和 'infra/config/index' 这行都是 infra V001 建的，
-- 而 infra dependsOn system 意味着 system 的迁移先跑 —— 把这几段搬去 system 会在建表前执行。
-- 谁插入的谁清理，顺序上也只有这一种选择。

-- 配置管理菜单从「基础设施」移到「系统管理」下。
-- 用 UPDATE 而不是删旧建新：菜单 id 若变化，已授权给角色的 system_role_menus 会失联，
-- 表现为「升级后管理员突然看不到这个菜单」。
UPDATE system_menus
SET parent_id = (SELECT id FROM system_menus WHERE path = '/system' AND type = 1 LIMIT 1),
    component = 'system/setting/index',
    path = 'setting',
    name = '系统设置',
    permission = 'system:setting:query'
WHERE component = 'infra/config/index';

-- 改配置是写操作，与查询分权：运营可以看，不一定该改
INSERT INTO system_menus (name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at)
SELECT '设置修改', 'system:setting:update', 3,
       (SELECT id FROM system_menus WHERE component = 'system/setting/index' LIMIT 1),
       '', NULL, NULL, 1, 1, 0, 0
WHERE EXISTS (SELECT 1 FROM system_menus WHERE component = 'system/setting/index')
  AND NOT EXISTS (SELECT 1 FROM system_menus WHERE permission = 'system:setting:update');

INSERT INTO system_role_menus (role_id, menu_id, created_at)
SELECT 1, id, 0 FROM system_menus
WHERE permission IN ('system:setting:query', 'system:setting:update')
  AND NOT EXISTS (SELECT 1 FROM system_role_menus rm WHERE rm.role_id = 1 AND rm.menu_id = system_menus.id);
