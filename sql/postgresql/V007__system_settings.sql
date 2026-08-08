-- 全局设置归入 system（SYSTEM_CONFIG_SPEC）
--
-- 命名用 settings 而不是 configs：本系统同时存在 config/*.conf（部署期，改了要重启）
-- 与本表（运行期，后台改完立即生效）。两者生命周期不同，共用一个词会让
-- 「改一下配置」这句话无法回答改的是哪个。
--
-- 配置是全局概念，不是基础设施概念 —— 基础设施是文件存储、任务、日志这类东西。
-- 放错模块的直接代价是 module-member 为了不依赖 infra，专门造了一个端口加适配器
-- 去读配置；表搬进 system 后那层绕路可以整个删掉。
--
-- 迁移仍写在 infra 名下：system_* 各表历来都由 infra 的迁移创建（DB-MIG-7A），
-- 迁移归属与代码归属是两件事，这里只跟随既有约定，不另开一套。

CREATE TABLE IF NOT EXISTS system_settings (
    id          BIGSERIAL PRIMARY KEY,
    -- 归属模块，如 member / cs / payment
    category    VARCHAR(64)  NOT NULL,
    setting_key VARCHAR(128) NOT NULL,
    -- 一律存字符串，真实类型由代码里的 SettingDefinition 决定；
    -- 数据库不做类型分裂，免得为一个布尔值再开一列
    value       TEXT         NOT NULL,
    -- 冗余自代码定义，供后台渲染控件；权威仍在代码
    value_type  SMALLINT     NOT NULL DEFAULT 0,
    name        VARCHAR(128) NOT NULL,
    remark      TEXT,
    created_at  BIGINT       NOT NULL DEFAULT 0,
    updated_at  BIGINT       NOT NULL DEFAULT 0
);

-- key 全局唯一：两处写同一个 key 会互相覆盖，运行期只表现为「改了没生效」，极难定位
CREATE UNIQUE INDEX IF NOT EXISTS uk_system_settings_key ON system_settings (setting_key);
CREATE INDEX IF NOT EXISTS idx_system_settings_category ON system_settings (category);

-- infra_configs 生产无有效数据（唯一使用方是尚未上线的签到周期配置，2026-08-07 确认），
-- 故不搬数据、不留过渡期双表 —— 空表加死代码的成本比删掉高，
-- 下一个人得先花时间确认它是不是还在用。
DROP TABLE IF EXISTS infra_configs;

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
