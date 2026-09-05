-- infra 模块（postgresql）—— 1.0.0 beta1 合并基线。
--
-- 🔴 **只给全新数据库用。** 由原来的 10 个迁移脚本按执行顺序拼接而成：
-- 顺序不变、语句不变，所以结果与逐条执行完全一致。
--
-- 为什么是拼接而不是导出结构快照：这些脚本里有 init_data / seed_menus 这类
-- **种子数据**，`pg_dump --schema-only` 会把它们丢掉，而只导结构就得再手工把
-- INSERT 补回来——那一步没有任何东西能验证对错。拼接则由构造保证等价。
--
-- 拼接的代价是留下了少量互相抵消的步骤（先加列、后改列）。它们无害，但**不要**
-- 试图"顺手清理"：清理一次就等于重新引入一个没人验证过的结构。
--
-- 存量库怎么办：本发布不提供原地升级。Neton 的迁移器按 checksum 校验，V001 变了
-- 就会拒绝启动——这是有意的，见 MigrationEngine 的 CHECKSUM_MISMATCH。
--
-- 加新东西请新增 V002、V003…，不要改这个文件。


-- ─────────────────────────────────────────────────────────────
-- 原 V001__create_tables.sql
-- ─────────────────────────────────────────────────────────────

-- =============================================
-- neton-application 全部建表语句 (PostgreSQL)
-- 包含: module-system + module-infra + provider
-- =============================================

-- ===================
-- module-system
-- ===================

CREATE TABLE IF NOT EXISTS system_users (
    id BIGSERIAL PRIMARY KEY,
    username VARCHAR(64) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    nickname VARCHAR(64) NOT NULL,
    email VARCHAR(128),
    mobile VARCHAR(32),
    avatar VARCHAR(512),
    status SMALLINT NOT NULL DEFAULT 0,
    deleted SMALLINT NOT NULL DEFAULT 0,
    created_at BIGINT NOT NULL DEFAULT 0,
    updated_at BIGINT NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_system_users_username ON system_users(username);

CREATE TABLE IF NOT EXISTS system_roles (
    id BIGSERIAL PRIMARY KEY,
    code VARCHAR(64) NOT NULL,
    name VARCHAR(64) NOT NULL,
    description VARCHAR(512),
    sort INT NOT NULL DEFAULT 0,
    status SMALLINT NOT NULL DEFAULT 0,
    created_at BIGINT NOT NULL DEFAULT 0,
    updated_at BIGINT NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_system_roles_code ON system_roles(code);

CREATE TABLE IF NOT EXISTS system_menus (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(64) NOT NULL,
    permission VARCHAR(128),
    type SMALLINT NOT NULL DEFAULT 0,
    parent_id BIGINT NOT NULL DEFAULT 0,
    path VARCHAR(255),
    component VARCHAR(255),
    icon VARCHAR(128),
    sort INT NOT NULL DEFAULT 0,
    status SMALLINT NOT NULL DEFAULT 0,
    created_at BIGINT NOT NULL DEFAULT 0,
    updated_at BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS system_user_roles (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    created_at BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_system_user_roles_user ON system_user_roles(user_id);
CREATE INDEX IF NOT EXISTS idx_system_user_roles_role ON system_user_roles(role_id);

CREATE TABLE IF NOT EXISTS system_role_menus (
    id BIGSERIAL PRIMARY KEY,
    role_id BIGINT NOT NULL,
    menu_id BIGINT NOT NULL,
    created_at BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_system_role_menus_role ON system_role_menus(role_id);

CREATE TABLE IF NOT EXISTS system_depts (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(64) NOT NULL,
    parent_id BIGINT NOT NULL DEFAULT 0,
    sort INT NOT NULL DEFAULT 0,
    leader_user_id BIGINT,
    status SMALLINT NOT NULL DEFAULT 0,
    created_at BIGINT NOT NULL DEFAULT 0,
    updated_at BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS system_posts (
    id BIGSERIAL PRIMARY KEY,
    code VARCHAR(64) NOT NULL,
    name VARCHAR(64) NOT NULL,
    sort INT NOT NULL DEFAULT 0,
    status SMALLINT NOT NULL DEFAULT 0,
    created_at BIGINT NOT NULL DEFAULT 0,
    updated_at BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS system_dict_types (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    type VARCHAR(128) NOT NULL,
    status SMALLINT NOT NULL DEFAULT 0,
    remark VARCHAR(512),
    created_at BIGINT NOT NULL DEFAULT 0,
    updated_at BIGINT NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_system_dict_types_type ON system_dict_types(type);

CREATE TABLE IF NOT EXISTS system_dict_data (
    id BIGSERIAL PRIMARY KEY,
    dict_type VARCHAR(128) NOT NULL,
    label VARCHAR(128) NOT NULL,
    value VARCHAR(128) NOT NULL,
    sort INT NOT NULL DEFAULT 0,
    status SMALLINT NOT NULL DEFAULT 0,
    remark VARCHAR(512),
    created_at BIGINT NOT NULL DEFAULT 0,
    updated_at BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_system_dict_data_type ON system_dict_data(dict_type);

CREATE TABLE IF NOT EXISTS system_notices (
    id BIGSERIAL PRIMARY KEY,
    title VARCHAR(255) NOT NULL,
    content TEXT NOT NULL,
    type SMALLINT NOT NULL DEFAULT 0,
    status SMALLINT NOT NULL DEFAULT 0,
    created_at BIGINT NOT NULL DEFAULT 0,
    updated_at BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS system_login_logs (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT,
    username VARCHAR(64),
    user_ip VARCHAR(64),
    user_agent VARCHAR(512),
    login_result SMALLINT NOT NULL DEFAULT 0,
    created_at BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_system_login_logs_user ON system_login_logs(user_id);

CREATE TABLE IF NOT EXISTS system_operate_logs (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT,
    module VARCHAR(64) NOT NULL,
    name VARCHAR(64) NOT NULL,
    operate_type SMALLINT NOT NULL DEFAULT 0,
    request_method VARCHAR(16),
    request_url VARCHAR(512),
    request_params TEXT,
    response_result TEXT,
    user_ip VARCHAR(64),
    duration INT NOT NULL DEFAULT 0,
    result_code INT NOT NULL DEFAULT 0,
    created_at BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_system_operate_logs_user ON system_operate_logs(user_id);

-- ===================
-- module-infra
-- ===================

CREATE TABLE IF NOT EXISTS infra_configs (
    id BIGSERIAL PRIMARY KEY,
    category VARCHAR(128) NOT NULL DEFAULT '',
    config_key VARCHAR(128) NOT NULL,
    value TEXT NOT NULL,
    type SMALLINT NOT NULL DEFAULT 0,
    name VARCHAR(128) NOT NULL,
    remark VARCHAR(512),
    created_at BIGINT NOT NULL DEFAULT 0,
    updated_at BIGINT NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_infra_configs_key ON infra_configs(config_key);

CREATE TABLE IF NOT EXISTS infra_files (
    id BIGSERIAL PRIMARY KEY,
    config_id BIGINT,
    name VARCHAR(255) NOT NULL,
    path VARCHAR(512) NOT NULL,
    url VARCHAR(512),
    mime_type VARCHAR(128),
    size BIGINT NOT NULL DEFAULT 0,
    created_at BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS infra_file_configs (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    storage SMALLINT NOT NULL DEFAULT 0,
    config TEXT NOT NULL DEFAULT '',
    master SMALLINT NOT NULL DEFAULT 0,
    remark VARCHAR(512),
    created_at BIGINT NOT NULL DEFAULT 0,
    updated_at BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS infra_api_access_logs (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT,
    user_type SMALLINT NOT NULL DEFAULT 0,
    application_name VARCHAR(128),
    request_method VARCHAR(16),
    request_url VARCHAR(512) NOT NULL,
    request_params TEXT,
    response_body TEXT,
    user_ip VARCHAR(64),
    user_agent VARCHAR(512),
    operate_module VARCHAR(64),
    operate_name VARCHAR(64),
    operate_type SMALLINT NOT NULL DEFAULT 0,
    begin_time BIGINT NOT NULL DEFAULT 0,
    end_time BIGINT NOT NULL DEFAULT 0,
    duration INT NOT NULL DEFAULT 0,
    result_code INT NOT NULL DEFAULT 0,
    result_msg TEXT,
    created_at BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS infra_api_error_logs (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT,
    user_type SMALLINT NOT NULL DEFAULT 0,
    application_name VARCHAR(128),
    request_method VARCHAR(16),
    request_url VARCHAR(512) NOT NULL,
    request_params TEXT,
    user_ip VARCHAR(64),
    user_agent VARCHAR(512),
    exception_name VARCHAR(255),
    exception_message TEXT,
    exception_stack_trace TEXT,
    process_status SMALLINT NOT NULL DEFAULT 0,
    process_user_id BIGINT,
    process_time BIGINT,
    created_at BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS infra_jobs (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    handler_name VARCHAR(128) NOT NULL,
    handler_param VARCHAR(512),
    cron_expression VARCHAR(64),
    retry_count INT NOT NULL DEFAULT 0,
    retry_interval INT NOT NULL DEFAULT 0,
    status SMALLINT NOT NULL DEFAULT 0,
    created_at BIGINT NOT NULL DEFAULT 0,
    updated_at BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS infra_job_logs (
    id BIGSERIAL PRIMARY KEY,
    job_id BIGINT NOT NULL,
    handler_name VARCHAR(128) NOT NULL,
    handler_param VARCHAR(512),
    execute_index INT NOT NULL DEFAULT 1,
    begin_time BIGINT NOT NULL DEFAULT 0,
    end_time BIGINT,
    duration INT,
    status SMALLINT NOT NULL DEFAULT 0,
    result_msg TEXT,
    created_at BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_infra_job_logs_job ON infra_job_logs(job_id);

-- ===================
-- provider (messaging + social login + notification)
-- ===================

CREATE TABLE IF NOT EXISTS system_message_channels (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    code VARCHAR(64) NOT NULL,
    type VARCHAR(32) NOT NULL,
    config TEXT,
    status SMALLINT NOT NULL DEFAULT 0,
    remark VARCHAR(512),
    deleted SMALLINT NOT NULL DEFAULT 0,
    created_at BIGINT NOT NULL DEFAULT 0,
    updated_at BIGINT NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_system_message_channels_code ON system_message_channels(code);

CREATE TABLE IF NOT EXISTS system_message_templates (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    code VARCHAR(64) NOT NULL,
    content TEXT NOT NULL,
    params TEXT,
    channel_id BIGINT NOT NULL DEFAULT 0,
    type SMALLINT NOT NULL DEFAULT 0,
    status SMALLINT NOT NULL DEFAULT 0,
    remark VARCHAR(512),
    deleted SMALLINT NOT NULL DEFAULT 0,
    created_at BIGINT NOT NULL DEFAULT 0,
    updated_at BIGINT NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_system_message_templates_code ON system_message_templates(code);

CREATE TABLE IF NOT EXISTS system_message_logs (
    id BIGSERIAL PRIMARY KEY,
    channel_id BIGINT NOT NULL,
    template_id BIGINT,
    template_code VARCHAR(64),
    receiver VARCHAR(255) NOT NULL,
    content TEXT,
    params TEXT,
    send_status SMALLINT NOT NULL DEFAULT 0,
    send_time BIGINT,
    error_message TEXT,
    user_id BIGINT,
    user_type SMALLINT NOT NULL DEFAULT 0,
    created_at BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_system_message_logs_channel ON system_message_logs(channel_id);
CREATE INDEX IF NOT EXISTS idx_system_message_logs_receiver ON system_message_logs(receiver);

CREATE TABLE IF NOT EXISTS system_social_users (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL DEFAULT 0,
    user_type SMALLINT NOT NULL DEFAULT 0,
    social_type VARCHAR(32) NOT NULL,
    open_id VARCHAR(128) NOT NULL,
    token VARCHAR(512),
    raw_token_info TEXT,
    nickname VARCHAR(64),
    avatar VARCHAR(512),
    raw_user_info TEXT,
    deleted SMALLINT NOT NULL DEFAULT 0,
    created_at BIGINT NOT NULL DEFAULT 0,
    updated_at BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_system_social_users_user ON system_social_users(user_id, user_type);
CREATE INDEX IF NOT EXISTS idx_system_social_users_social ON system_social_users(social_type, open_id);

CREATE TABLE IF NOT EXISTS system_notification_templates (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    code VARCHAR(64) NOT NULL,
    type SMALLINT NOT NULL DEFAULT 0,
    message_template_id BIGINT NOT NULL DEFAULT 0,
    params TEXT,
    status SMALLINT NOT NULL DEFAULT 0,
    remark VARCHAR(512),
    deleted SMALLINT NOT NULL DEFAULT 0,
    created_at BIGINT NOT NULL DEFAULT 0,
    updated_at BIGINT NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_system_notification_templates_code ON system_notification_templates(code);

CREATE TABLE IF NOT EXISTS system_notify_messages (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL DEFAULT 0,
    user_type SMALLINT NOT NULL DEFAULT 0,
    template_id BIGINT NOT NULL DEFAULT 0,
    template_code VARCHAR(64),
    template_type SMALLINT NOT NULL DEFAULT 0,
    template_nickname VARCHAR(64),
    template_content TEXT,
    template_params TEXT,
    read_status SMALLINT NOT NULL DEFAULT 0,
    read_time BIGINT,
    created_at BIGINT NOT NULL DEFAULT 0,
    updated_at BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_system_notify_messages_user ON system_notify_messages(user_id, user_type);
CREATE INDEX IF NOT EXISTS idx_system_notify_messages_read ON system_notify_messages(user_id, read_status);


-- ─────────────────────────────────────────────────────────────
-- 原 V002__init_data.sql
-- ─────────────────────────────────────────────────────────────

-- application V003: 系统管理/基础设施菜单 + admin/role + 字典 seed (从 dev 库导出)
SET search_path = public;

INSERT INTO system_users (id, username, password_hash, nickname, status, created_at, updated_at) VALUES (1, 'admin', 'pbkdf2-sha256$210000$bG9uZ2xpdmVuZXRvbjIwMg$Vkgw7lb_za84bhyNLsFSB6fwt3hAf2HTcB6BhGca5EY', '超级管理员', 1, 0, 0) ON CONFLICT (id) DO NOTHING;

INSERT INTO system_roles (id, code, name, description, sort, status, created_at, updated_at) VALUES (1, 'super_admin', '超级管理员', '拥有所有权限', 0, 1, 0, 0) ON CONFLICT (id) DO NOTHING;

INSERT INTO system_user_roles (id, user_id, role_id, created_at) VALUES (1, 1, 1, 0) ON CONFLICT (id) DO NOTHING;

INSERT INTO system_menus (id, name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at) VALUES (1, '系统管理', '', 1, 0, '/system', NULL, 'ant-design:setting-outlined', 1, 1, 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_menus (id, name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at) VALUES (2, '基础设施', '', 1, 0, '/infra', NULL, 'ant-design:tool-outlined', 2, 1, 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_menus (id, name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at) VALUES (100, '用户管理', 'system:user:list', 2, 1, 'user', 'system/user/index', 'ant-design:user-outlined', 1, 1, 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_menus (id, name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at) VALUES (101, '角色管理', 'system:role:list', 2, 1, 'role', 'system/role/index', 'ant-design:team-outlined', 2, 1, 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_menus (id, name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at) VALUES (102, '菜单管理', 'system:menu:list', 2, 1, 'menu', 'system/menu/index', 'ant-design:menu-outlined', 3, 1, 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_menus (id, name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at) VALUES (103, '部门管理', 'system:dept:list', 2, 1, 'dept', 'system/dept/index', 'ant-design:apartment-outlined', 4, 1, 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_menus (id, name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at) VALUES (104, '岗位管理', 'system:post:list', 2, 1, 'post', 'system/post/index', 'ant-design:idcard-outlined', 5, 1, 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_menus (id, name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at) VALUES (105, '字典管理', 'system:dict:list', 2, 1, 'dict', 'system/dict/index', 'ant-design:book-outlined', 6, 1, 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_menus (id, name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at) VALUES (106, '通知公告', 'system:notice:list', 2, 1, 'notice', 'system/notice/index', 'ant-design:notification-outlined', 7, 1, 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_menus (id, name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at) VALUES (107, '操作日志', 'system:operate-log:page', 2, 1, 'operate-log', 'system/operatelog/index', 'ant-design:file-text-outlined', 8, 1, 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_menus (id, name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at) VALUES (108, '登录日志', 'system:login-log:page', 2, 1, 'login-log', 'system/loginlog/index', 'ant-design:login-outlined', 9, 1, 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_menus (id, name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at) VALUES (109, '消息中心', '', 1, 1, 'message', NULL, 'ant-design:message-outlined', 10, 1, 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_menus (id, name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at) VALUES (110, '站内信', '', 1, 1, 'notify', NULL, 'ant-design:bell-outlined', 11, 1, 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_menus (id, name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at) VALUES (111, '短信管理', '', 1, 1, 'sms', NULL, 'ant-design:mobile-outlined', 12, 1, 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_menus (id, name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at) VALUES (112, '邮件管理', '', 1, 1, 'mail', NULL, 'ant-design:mail-outlined', 13, 1, 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_menus (id, name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at) VALUES (113, '社交用户', 'system:social-user:query', 2, 1, 'social-user', 'system/social/user/index', 'ant-design:share-alt-outlined', 14, 1, 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_menus (id, name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at) VALUES (1000, '用户查询', 'system:user:query', 3, 100, '', NULL, '', 1, 1, 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_menus (id, name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at) VALUES (1001, '用户新增', 'system:user:create', 3, 100, '', NULL, '', 2, 1, 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_menus (id, name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at) VALUES (1002, '用户修改', 'system:user:update', 3, 100, '', NULL, '', 3, 1, 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_menus (id, name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at) VALUES (1003, '用户删除', 'system:user:delete', 3, 100, '', NULL, '', 4, 1, 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_menus (id, name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at) VALUES (1004, '重置密码', 'system:user:update-password', 3, 100, '', NULL, '', 5, 1, 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_menus (id, name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at) VALUES (1010, '角色查询', 'system:role:query', 3, 101, '', NULL, '', 1, 1, 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_menus (id, name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at) VALUES (1011, '角色新增', 'system:role:create', 3, 101, '', NULL, '', 2, 1, 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_menus (id, name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at) VALUES (1012, '角色修改', 'system:role:update', 3, 101, '', NULL, '', 3, 1, 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_menus (id, name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at) VALUES (1013, '角色删除', 'system:role:delete', 3, 101, '', NULL, '', 4, 1, 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_menus (id, name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at) VALUES (1014, '权限查询', 'system:permission:query', 3, 101, '', NULL, '', 5, 1, 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_menus (id, name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at) VALUES (1015, '权限分配', 'system:permission:assign', 3, 101, '', NULL, '', 6, 1, 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_menus (id, name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at) VALUES (1020, '菜单查询', 'system:menu:query', 3, 102, '', NULL, '', 1, 1, 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_menus (id, name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at) VALUES (1021, '菜单新增', 'system:menu:create', 3, 102, '', NULL, '', 2, 1, 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_menus (id, name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at) VALUES (1022, '菜单修改', 'system:menu:update', 3, 102, '', NULL, '', 3, 1, 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_menus (id, name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at) VALUES (1023, '菜单删除', 'system:menu:delete', 3, 102, '', NULL, '', 4, 1, 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_menus (id, name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at) VALUES (1030, '部门查询', 'system:dept:query', 3, 103, '', NULL, '', 1, 1, 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_menus (id, name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at) VALUES (1031, '部门新增', 'system:dept:create', 3, 103, '', NULL, '', 2, 1, 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_menus (id, name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at) VALUES (1032, '部门修改', 'system:dept:update', 3, 103, '', NULL, '', 3, 1, 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_menus (id, name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at) VALUES (1033, '部门删除', 'system:dept:delete', 3, 103, '', NULL, '', 4, 1, 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_menus (id, name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at) VALUES (1040, '岗位查询', 'system:post:query', 3, 104, '', NULL, '', 1, 1, 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_menus (id, name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at) VALUES (1041, '岗位新增', 'system:post:create', 3, 104, '', NULL, '', 2, 1, 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_menus (id, name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at) VALUES (1042, '岗位修改', 'system:post:update', 3, 104, '', NULL, '', 3, 1, 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_menus (id, name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at) VALUES (1043, '岗位删除', 'system:post:delete', 3, 104, '', NULL, '', 4, 1, 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_menus (id, name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at) VALUES (1050, '字典查询', 'system:dict:query', 3, 105, '', NULL, '', 1, 1, 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_menus (id, name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at) VALUES (1051, '字典新增', 'system:dict:create', 3, 105, '', NULL, '', 2, 1, 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_menus (id, name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at) VALUES (1052, '字典修改', 'system:dict:update', 3, 105, '', NULL, '', 3, 1, 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_menus (id, name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at) VALUES (1053, '字典删除', 'system:dict:delete', 3, 105, '', NULL, '', 4, 1, 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_menus (id, name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at) VALUES (1060, '公告查询', 'system:notice:query', 3, 106, '', NULL, '', 1, 1, 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_menus (id, name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at) VALUES (1061, '公告新增', 'system:notice:create', 3, 106, '', NULL, '', 2, 1, 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_menus (id, name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at) VALUES (1062, '公告修改', 'system:notice:update', 3, 106, '', NULL, '', 3, 1, 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_menus (id, name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at) VALUES (1063, '公告删除', 'system:notice:delete', 3, 106, '', NULL, '', 4, 1, 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_menus (id, name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at) VALUES (1090, '消息渠道', 'system:message-channel:query', 2, 109, 'message-channel', 'system/sms/channel/index', '', 1, 1, 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_menus (id, name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at) VALUES (1091, '消息模板', 'system:message-template:query', 2, 109, 'message-template', 'system/sms/template/index', '', 2, 1, 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_menus (id, name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at) VALUES (1092, '消息记录', 'system:message-log:query', 2, 109, 'message-log', 'system/sms/log/index', '', 3, 1, 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_menus (id, name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at) VALUES (1100, '通知模板', 'system:notify-template:query', 2, 110, 'notify-template', 'system/notify/template/index', '', 1, 1, 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_menus (id, name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at) VALUES (1101, '通知消息', 'system:notify-message:query', 2, 110, 'notify-message', 'system/notify/message/index', '', 2, 1, 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_menus (id, name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at) VALUES (1110, '短信渠道', 'system:sms-channel:query', 2, 111, 'sms-channel', 'system/sms/channel/index', '', 1, 1, 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_menus (id, name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at) VALUES (1111, '短信模板', 'system:sms-template:query', 2, 111, 'sms-template', 'system/sms/template/index', '', 2, 1, 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_menus (id, name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at) VALUES (1112, '短信日志', 'system:sms-log:query', 2, 111, 'sms-log', 'system/sms/log/index', '', 3, 1, 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_menus (id, name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at) VALUES (1120, '邮箱账号', 'system:mail-account:query', 2, 112, 'mail-account', 'system/mail/account/index', '', 1, 1, 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_menus (id, name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at) VALUES (1121, '邮件模板', 'system:mail-template:query', 2, 112, 'mail-template', 'system/mail/template/index', '', 2, 1, 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_menus (id, name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at) VALUES (1122, '邮件记录', 'system:mail-log:query', 2, 112, 'mail-log', 'system/mail/log/index', '', 3, 1, 0, 0) ON CONFLICT (id) DO NOTHING;


INSERT INTO system_dict_types (id, name, type, status, remark, created_at, updated_at) VALUES (1, '用户性别', 'system_user_sex', 1, '用户性别', 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_dict_types (id, name, type, status, remark, created_at, updated_at) VALUES (2, '通用状态', 'common_status', 1, '通用状态', 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_dict_types (id, name, type, status, remark, created_at, updated_at) VALUES (3, '菜单类型', 'system_menu_type', 1, '菜单类型', 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_dict_types (id, name, type, status, remark, created_at, updated_at) VALUES (4, '角色类型', 'system_role_type', 1, '角色类型', 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_dict_types (id, name, type, status, remark, created_at, updated_at) VALUES (5, '数据范围', 'system_data_scope', 1, '数据范围', 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_dict_types (id, name, type, status, remark, created_at, updated_at) VALUES (6, '公告类型', 'system_notice_type', 1, '公告类型', 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_dict_types (id, name, type, status, remark, created_at, updated_at) VALUES (7, '登录类型', 'system_login_type', 1, '登录日志类型', 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_dict_types (id, name, type, status, remark, created_at, updated_at) VALUES (8, '登录结果', 'system_login_result', 1, '登录结果', 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_dict_types (id, name, type, status, remark, created_at, updated_at) VALUES (9, '配置类型', 'infra_config_type', 1, '参数配置类型', 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_dict_types (id, name, type, status, remark, created_at, updated_at) VALUES (10, '布尔类型', 'infra_boolean_string', 1, '布尔类型', 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_dict_types (id, name, type, status, remark, created_at, updated_at) VALUES (11, '操作类型', 'infra_operate_type', 1, '操作类型', 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_dict_types (id, name, type, status, remark, created_at, updated_at) VALUES (12, '用户类型', 'user_type', 1, '用户类型', 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_dict_types (id, name, type, status, remark, created_at, updated_at) VALUES (13, '短信渠道编码', 'system_sms_channel_code', 1, '短信渠道编码', 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_dict_types (id, name, type, status, remark, created_at, updated_at) VALUES (14, '短信模板类型', 'system_sms_template_type', 1, '短信模板类型', 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_dict_types (id, name, type, status, remark, created_at, updated_at) VALUES (15, '短信发送状态', 'system_sms_send_status', 1, '短信发送状态', 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_dict_types (id, name, type, status, remark, created_at, updated_at) VALUES (16, '短信接收状态', 'system_sms_receive_status', 1, '短信接收状态', 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_dict_types (id, name, type, status, remark, created_at, updated_at) VALUES (17, '邮件发送状态', 'system_mail_send_status', 1, '邮件发送状态', 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_dict_types (id, name, type, status, remark, created_at, updated_at) VALUES (18, '通知模板类型', 'system_notify_template_type', 1, '通知模板类型', 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_dict_types (id, name, type, status, remark, created_at, updated_at) VALUES (19, '社交类型', 'system_social_type', 1, '社交类型', 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_dict_types (id, name, type, status, remark, created_at, updated_at) VALUES (20, '终端', 'terminal', 1, '终端', 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_dict_types (id, name, type, status, remark, created_at, updated_at) VALUES (21, '文件存储器', 'infra_file_storage', 1, '文件存储器', 0, 0) ON CONFLICT (id) DO NOTHING;

INSERT INTO system_dict_data (id, dict_type, label, value, sort, status, remark, created_at, updated_at) VALUES (1, 'common_status', '开启', '0', 1, 1, '', 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_dict_data (id, dict_type, label, value, sort, status, remark, created_at, updated_at) VALUES (2, 'common_status', '关闭', '1', 2, 1, '', 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_dict_data (id, dict_type, label, value, sort, status, remark, created_at, updated_at) VALUES (3, 'system_user_sex', '男', '1', 1, 1, '', 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_dict_data (id, dict_type, label, value, sort, status, remark, created_at, updated_at) VALUES (4, 'system_user_sex', '女', '2', 2, 1, '', 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_dict_data (id, dict_type, label, value, sort, status, remark, created_at, updated_at) VALUES (5, 'system_user_sex', '未知', '0', 3, 1, '', 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_dict_data (id, dict_type, label, value, sort, status, remark, created_at, updated_at) VALUES (6, 'system_menu_type', '目录', '1', 1, 1, '', 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_dict_data (id, dict_type, label, value, sort, status, remark, created_at, updated_at) VALUES (7, 'system_menu_type', '菜单', '2', 2, 1, '', 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_dict_data (id, dict_type, label, value, sort, status, remark, created_at, updated_at) VALUES (8, 'system_menu_type', '按钮', '3', 3, 1, '', 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_dict_data (id, dict_type, label, value, sort, status, remark, created_at, updated_at) VALUES (9, 'system_role_type', '内置', '1', 1, 1, '', 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_dict_data (id, dict_type, label, value, sort, status, remark, created_at, updated_at) VALUES (10, 'system_role_type', '自定义', '2', 2, 1, '', 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_dict_data (id, dict_type, label, value, sort, status, remark, created_at, updated_at) VALUES (11, 'system_data_scope', '全部数据权限', '1', 1, 1, '', 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_dict_data (id, dict_type, label, value, sort, status, remark, created_at, updated_at) VALUES (12, 'system_data_scope', '指定部门数据权限', '2', 2, 1, '', 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_dict_data (id, dict_type, label, value, sort, status, remark, created_at, updated_at) VALUES (13, 'system_data_scope', '本部门数据权限', '3', 3, 1, '', 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_dict_data (id, dict_type, label, value, sort, status, remark, created_at, updated_at) VALUES (14, 'system_data_scope', '本部门及以下数据权限', '4', 4, 1, '', 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_dict_data (id, dict_type, label, value, sort, status, remark, created_at, updated_at) VALUES (15, 'system_data_scope', '仅本人数据权限', '5', 5, 1, '', 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_dict_data (id, dict_type, label, value, sort, status, remark, created_at, updated_at) VALUES (16, 'system_notice_type', '通知', '1', 1, 1, '', 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_dict_data (id, dict_type, label, value, sort, status, remark, created_at, updated_at) VALUES (17, 'system_notice_type', '公告', '2', 2, 1, '', 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_dict_data (id, dict_type, label, value, sort, status, remark, created_at, updated_at) VALUES (18, 'system_login_type', '使用账号登录', '100', 0, 1, '', 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_dict_data (id, dict_type, label, value, sort, status, remark, created_at, updated_at) VALUES (19, 'system_login_type', '使用社交登录', '101', 1, 1, '', 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_dict_data (id, dict_type, label, value, sort, status, remark, created_at, updated_at) VALUES (20, 'system_login_type', '使用手机登陆', '103', 2, 1, '', 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_dict_data (id, dict_type, label, value, sort, status, remark, created_at, updated_at) VALUES (21, 'system_login_type', '使用短信登陆', '104', 3, 1, '', 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_dict_data (id, dict_type, label, value, sort, status, remark, created_at, updated_at) VALUES (22, 'system_login_type', '自助注册', '200', 10, 1, '', 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_dict_data (id, dict_type, label, value, sort, status, remark, created_at, updated_at) VALUES (23, 'system_login_type', '管理员创建', '201', 11, 1, '', 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_dict_data (id, dict_type, label, value, sort, status, remark, created_at, updated_at) VALUES (24, 'system_login_result', '登录成功', '0', 1, 1, '', 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_dict_data (id, dict_type, label, value, sort, status, remark, created_at, updated_at) VALUES (25, 'system_login_result', '登录失败', '10', 2, 1, '', 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_dict_data (id, dict_type, label, value, sort, status, remark, created_at, updated_at) VALUES (26, 'system_login_result', '退出成功', '20', 3, 1, '', 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_dict_data (id, dict_type, label, value, sort, status, remark, created_at, updated_at) VALUES (27, 'system_login_result', '退出失败', '30', 4, 1, '', 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_dict_data (id, dict_type, label, value, sort, status, remark, created_at, updated_at) VALUES (28, 'infra_config_type', '系统内置', '1', 1, 1, '', 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_dict_data (id, dict_type, label, value, sort, status, remark, created_at, updated_at) VALUES (29, 'infra_config_type', '自定义', '2', 2, 1, '', 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_dict_data (id, dict_type, label, value, sort, status, remark, created_at, updated_at) VALUES (30, 'infra_boolean_string', '是', 'true', 1, 1, '', 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_dict_data (id, dict_type, label, value, sort, status, remark, created_at, updated_at) VALUES (31, 'infra_boolean_string', '否', 'false', 2, 1, '', 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_dict_data (id, dict_type, label, value, sort, status, remark, created_at, updated_at) VALUES (32, 'infra_operate_type', '查询', '1', 1, 1, '', 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_dict_data (id, dict_type, label, value, sort, status, remark, created_at, updated_at) VALUES (33, 'infra_operate_type', '新增', '2', 2, 1, '', 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_dict_data (id, dict_type, label, value, sort, status, remark, created_at, updated_at) VALUES (34, 'infra_operate_type', '修改', '3', 3, 1, '', 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_dict_data (id, dict_type, label, value, sort, status, remark, created_at, updated_at) VALUES (35, 'infra_operate_type', '删除', '4', 4, 1, '', 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_dict_data (id, dict_type, label, value, sort, status, remark, created_at, updated_at) VALUES (36, 'infra_operate_type', '导出', '5', 5, 1, '', 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_dict_data (id, dict_type, label, value, sort, status, remark, created_at, updated_at) VALUES (37, 'infra_operate_type', '导入', '6', 6, 1, '', 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_dict_data (id, dict_type, label, value, sort, status, remark, created_at, updated_at) VALUES (38, 'user_type', '会员', '1', 1, 1, '', 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_dict_data (id, dict_type, label, value, sort, status, remark, created_at, updated_at) VALUES (39, 'user_type', '管理员', '2', 2, 1, '', 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_dict_data (id, dict_type, label, value, sort, status, remark, created_at, updated_at) VALUES (40, 'terminal', '未知', '0', 0, 1, '', 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_dict_data (id, dict_type, label, value, sort, status, remark, created_at, updated_at) VALUES (41, 'terminal', '浏览器', '10', 1, 1, '', 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_dict_data (id, dict_type, label, value, sort, status, remark, created_at, updated_at) VALUES (42, 'terminal', '小程序', '11', 2, 1, '', 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_dict_data (id, dict_type, label, value, sort, status, remark, created_at, updated_at) VALUES (43, 'terminal', 'APP', '20', 3, 1, '', 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_dict_data (id, dict_type, label, value, sort, status, remark, created_at, updated_at) VALUES (44, 'system_sms_template_type', '验证码', '1', 1, 1, '', 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_dict_data (id, dict_type, label, value, sort, status, remark, created_at, updated_at) VALUES (45, 'system_sms_template_type', '通知', '2', 2, 1, '', 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_dict_data (id, dict_type, label, value, sort, status, remark, created_at, updated_at) VALUES (46, 'system_sms_template_type', '营销', '3', 3, 1, '', 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_dict_data (id, dict_type, label, value, sort, status, remark, created_at, updated_at) VALUES (47, 'system_sms_send_status', '初始化', '0', 0, 1, '', 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_dict_data (id, dict_type, label, value, sort, status, remark, created_at, updated_at) VALUES (48, 'system_sms_send_status', '发送成功', '10', 1, 1, '', 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_dict_data (id, dict_type, label, value, sort, status, remark, created_at, updated_at) VALUES (49, 'system_sms_send_status', '发送失败', '20', 2, 1, '', 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_dict_data (id, dict_type, label, value, sort, status, remark, created_at, updated_at) VALUES (50, 'system_sms_receive_status', '初始化', '0', 0, 1, '', 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_dict_data (id, dict_type, label, value, sort, status, remark, created_at, updated_at) VALUES (51, 'system_sms_receive_status', '接收成功', '10', 1, 1, '', 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_dict_data (id, dict_type, label, value, sort, status, remark, created_at, updated_at) VALUES (52, 'system_sms_receive_status', '接收失败', '20', 2, 1, '', 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_dict_data (id, dict_type, label, value, sort, status, remark, created_at, updated_at) VALUES (53, 'system_mail_send_status', '初始化', '0', 0, 1, '', 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_dict_data (id, dict_type, label, value, sort, status, remark, created_at, updated_at) VALUES (54, 'system_mail_send_status', '发送成功', '10', 1, 1, '', 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_dict_data (id, dict_type, label, value, sort, status, remark, created_at, updated_at) VALUES (55, 'system_mail_send_status', '发送失败', '20', 2, 1, '', 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_dict_data (id, dict_type, label, value, sort, status, remark, created_at, updated_at) VALUES (56, 'system_notify_template_type', '站内信', '1', 1, 1, '', 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_dict_data (id, dict_type, label, value, sort, status, remark, created_at, updated_at) VALUES (57, 'system_notify_template_type', '短信', '2', 2, 1, '', 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_dict_data (id, dict_type, label, value, sort, status, remark, created_at, updated_at) VALUES (58, 'system_notify_template_type', '邮件', '3', 3, 1, '', 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_dict_data (id, dict_type, label, value, sort, status, remark, created_at, updated_at) VALUES (60, 'infra_file_storage', '本地存储', '10', 1, 1, '', 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_dict_data (id, dict_type, label, value, sort, status, remark, created_at, updated_at) VALUES (61, 'infra_file_storage', 'S3存储', '20', 2, 1, '', 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_menus (id, name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at) VALUES (200, '配置管理', 'infra:config:query', 2, 2, 'config', 'infra/config/index', 'ant-design:setting-outlined', 1, 1, 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_menus (id, name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at) VALUES (201, '文件管理', 'infra:file:query', 2, 2, 'file', 'infra/file/index', 'ant-design:file-outlined', 2, 1, 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_menus (id, name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at) VALUES (202, '文件配置', 'infra:file-config:query', 2, 2, 'file-config', 'infra/fileConfig/index', 'ant-design:folder-open-outlined', 3, 1, 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_menus (id, name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at) VALUES (203, '定时任务', 'infra:job:query', 2, 2, 'job', 'infra/job/index', 'ant-design:clock-circle-outlined', 4, 1, 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_menus (id, name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at) VALUES (204, 'API访问日志', 'infra:api-access-log:query', 2, 2, 'api-access-log', 'infra/apiAccessLog/index', 'ant-design:api-outlined', 5, 1, 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_menus (id, name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at) VALUES (205, 'API错误日志', 'infra:api-error-log:query', 2, 2, 'api-error-log', 'infra/apiErrorLog/index', 'ant-design:bug-outlined', 6, 1, 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_menus (id, name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at) VALUES (206, 'Redis监控', 'infra:redis:query', 2, 2, 'redis', 'infra/redis/index', 'ant-design:database-outlined', 7, 1, 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_menus (id, name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at) VALUES (2000, '配置查询', 'infra:config:query', 3, 200, '', NULL, '', 1, 1, 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_menus (id, name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at) VALUES (2001, '配置新增', 'infra:config:create', 3, 200, '', NULL, '', 2, 1, 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_menus (id, name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at) VALUES (2002, '配置修改', 'infra:config:update', 3, 200, '', NULL, '', 3, 1, 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_menus (id, name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at) VALUES (2003, '配置删除', 'infra:config:delete', 3, 200, '', NULL, '', 4, 1, 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_menus (id, name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at) VALUES (2010, '文件查询', 'infra:file:query', 3, 201, '', NULL, '', 1, 1, 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_menus (id, name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at) VALUES (2011, '文件上传', 'infra:file:upload', 3, 201, '', NULL, '', 2, 1, 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_menus (id, name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at) VALUES (2012, '文件删除', 'infra:file:delete', 3, 201, '', NULL, '', 3, 1, 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_menus (id, name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at) VALUES (2020, '文件配置查询', 'infra:file-config:query', 3, 202, '', NULL, '', 1, 1, 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_menus (id, name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at) VALUES (2021, '文件配置新增', 'infra:file-config:create', 3, 202, '', NULL, '', 2, 1, 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_menus (id, name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at) VALUES (2022, '文件配置修改', 'infra:file-config:update', 3, 202, '', NULL, '', 3, 1, 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_menus (id, name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at) VALUES (2023, '文件配置删除', 'infra:file-config:delete', 3, 202, '', NULL, '', 4, 1, 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_menus (id, name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at) VALUES (2030, '任务查询', 'infra:job:query', 3, 203, '', NULL, '', 1, 1, 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_menus (id, name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at) VALUES (2031, '任务新增', 'infra:job:create', 3, 203, '', NULL, '', 2, 1, 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_menus (id, name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at) VALUES (2032, '任务修改', 'infra:job:update', 3, 203, '', NULL, '', 3, 1, 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_menus (id, name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at) VALUES (2033, '任务删除', 'infra:job:delete', 3, 203, '', NULL, '', 4, 1, 0, 0) ON CONFLICT (id) DO NOTHING;
INSERT INTO system_menus (id, name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at) VALUES (2034, '任务触发', 'infra:job:trigger', 3, 203, '', NULL, '', 5, 1, 0, 0) ON CONFLICT (id) DO NOTHING;


-- ─────────────────────────────────────────────────────────────
-- 原 V003__fix_common_status_dictionary.sql
-- ─────────────────────────────────────────────────────────────

-- Align the shared status dictionary with the platform-wide status convention.
UPDATE system_dict_data
SET label = '启用', sort = 1
WHERE dict_type = 'common_status' AND value = '1';

UPDATE system_dict_data
SET label = '停用', sort = 2
WHERE dict_type = 'common_status' AND value = '0';


-- ─────────────────────────────────────────────────────────────
-- 原 V004__system_users_gender_profile_columns.sql
-- ─────────────────────────────────────────────────────────────

-- module-system schema drift fix + naming (P0).
--
-- model/User.kt 声明了 gender / deptId / remark，但 V001__create_tables 没有建这些列，
-- 全新库 migrate 后 admin 登录直接 500（`No column found with name: 'gender'`）。
-- 已应用的 V001 不可编辑（checksum 红线），走增量迁移。
--
-- 命名修正：性别字段标准命名为 gender（0=未知 1=男 2=女），不用 sex。
-- 存量库若曾手工/分支加过 sex 列，这里一并重命名兜底。
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_name = 'system_users' AND column_name = 'sex') THEN
        ALTER TABLE system_users RENAME COLUMN sex TO gender;
    END IF;
END $$;
ALTER TABLE system_users ADD COLUMN IF NOT EXISTS gender SMALLINT NOT NULL DEFAULT 0;
ALTER TABLE system_users ADD COLUMN IF NOT EXISTS dept_id BIGINT NOT NULL DEFAULT 0;
ALTER TABLE system_users ADD COLUMN IF NOT EXISTS remark VARCHAR(512);

-- 字典键随字段改名（V002 seed 用的是 system_user_sex）
UPDATE system_dict_types SET type = 'system_user_gender' WHERE type = 'system_user_sex';
UPDATE system_dict_data SET dict_type = 'system_user_gender' WHERE dict_type = 'system_user_sex';


-- ─────────────────────────────────────────────────────────────
-- 原 V005__add_infra_files_upload_columns.sql
-- ─────────────────────────────────────────────────────────────

-- infra_files 用户态上传元数据列(spec MODULE_MEMBER_PROFILE_SPEC §4.2)。
-- FileUploadLogic 落库依赖 file_id/owner_uid/business_type/sha256/status,
-- V001 建表时缺失,任何 /app/infra/file/upload 都会 42703 报错。
ALTER TABLE infra_files ADD COLUMN IF NOT EXISTS file_id VARCHAR(64);
ALTER TABLE infra_files ADD COLUMN IF NOT EXISTS owner_uid BIGINT;
ALTER TABLE infra_files ADD COLUMN IF NOT EXISTS business_type VARCHAR(64);
ALTER TABLE infra_files ADD COLUMN IF NOT EXISTS sha256 VARCHAR(64);
ALTER TABLE infra_files ADD COLUMN IF NOT EXISTS status SMALLINT NOT NULL DEFAULT 1;

CREATE UNIQUE INDEX IF NOT EXISTS idx_infra_files_file_id
    ON infra_files(file_id) WHERE file_id IS NOT NULL;
-- 幂等去重查询路径:owner + business_type + sha256
CREATE INDEX IF NOT EXISTS idx_infra_files_owner_biz_sha
    ON infra_files(owner_uid, business_type, sha256);


-- ─────────────────────────────────────────────────────────────
-- 原 V006__normalize_system_query_permissions.sql
-- ─────────────────────────────────────────────────────────────

-- Normalize system read permissions to resource-level `query` capabilities.
-- HTTP result shapes (page/list/get) are not separate RBAC capabilities.

WITH canonical(id, permission) AS (
    VALUES
        (100::bigint, 'system:user:query'),
        (101::bigint, 'system:role:query'),
        (102::bigint, 'system:menu:query'),
        (103::bigint, 'system:dept:query'),
        (104::bigint, 'system:post:query'),
        (105::bigint, 'system:dict:query'),
        (106::bigint, 'system:notice:query'),
        (107::bigint, 'system:operate-log:query'),
        (108::bigint, 'system:login-log:query')
)
UPDATE system_menus menu
   SET permission = canonical.permission,
       updated_at = (extract(epoch from now()) * 1000)::bigint
  FROM canonical
 WHERE menu.id = canonical.id
   AND menu.permission IS DISTINCT FROM canonical.permission;

-- Query used to be duplicated as a button below each page menu. The page now
-- carries the query capability, so remove only those redundant query buttons.
DELETE FROM system_role_menus
 WHERE menu_id IN (1000, 1010, 1020, 1030, 1040, 1050, 1060);

DELETE FROM system_menus
 WHERE id IN (1000, 1010, 1020, 1030, 1040, 1050, 1060)
   AND permission IN (
       'system:user:query',
       'system:role:query',
       'system:menu:query',
       'system:dept:query',
       'system:post:query',
       'system:dict:query',
       'system:notice:query'
   );

-- Product administrators retain the complete system administration surface.
INSERT INTO system_role_menus (role_id, menu_id, created_at)
SELECT
    role.id,
    menu.id,
    (extract(epoch from now()) * 1000)::bigint
FROM system_roles role
CROSS JOIN system_menus menu
WHERE role.code IN ('super_admin', 'admin')
  AND (
      menu.id = 1
      OR menu.permission LIKE 'system:%'
  )
  AND NOT EXISTS (
      SELECT 1
        FROM system_role_menus existing
       WHERE existing.role_id = role.id
         AND existing.menu_id = menu.id
  )
-- 🔴 定死顺序：见 cs 模块基线里的同类说明。没有 ORDER BY 时 serial 主键每次
-- 分配都不同，结构对拍会一直有假差异。
ORDER BY role.id, menu.id;

SELECT setval(
    pg_get_serial_sequence('system_menus', 'id'),
    (SELECT MAX(id) FROM system_menus)
);


-- ─────────────────────────────────────────────────────────────
-- 原 V007__drop_infra_configs.sql
-- ─────────────────────────────────────────────────────────────

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


-- ─────────────────────────────────────────────────────────────
-- 原 V008__domain_events.sql
-- ─────────────────────────────────────────────────────────────

-- 领域事件投递表（framework 的 DomainEventStore 端口在 infra 的落地实现）。
--
-- 事件在发布方的事务内写入本表，提交后由调度任务异步投递、失败退避重试。
-- 与业务写入同事务是关键：发布方回滚记录随之消失，发布方提交记录必然存在，
-- 不存在"主流程成功了事件却丢了"或"投递了并未发生的事件"。
--
-- 表归 infra 而不是框架：用什么载体投递（数据库 / 线程 / 中间件）是应用层的选择，
-- 框架只定义端口。

CREATE TABLE IF NOT EXISTS infra_domain_events (
    id              BIGSERIAL PRIMARY KEY,
    -- 事件类型标识，投递时据此还原事件对象
    event_type      VARCHAR(128) NOT NULL,
    -- 目标监听者；同一事件的多个监听者各一行，互不影响重试
    listener_id     VARCHAR(128) NOT NULL,
    payload         TEXT         NOT NULL,
    -- 0=待投递 1=投递中 2=已投递 3=终态失败
    status          SMALLINT     NOT NULL DEFAULT 0,
    attempts        INT          NOT NULL DEFAULT 0,
    next_attempt_at BIGINT       NOT NULL DEFAULT 0,
    last_error      TEXT,
    created_at      BIGINT       NOT NULL DEFAULT 0,
    updated_at      BIGINT       NOT NULL DEFAULT 0
);

-- 投递查询固定按 (status, next_attempt_at) 过滤，缺这个索引在积压时会全表扫
CREATE INDEX IF NOT EXISTS idx_infra_domain_events_due
    ON infra_domain_events (status, next_attempt_at);


-- ─────────────────────────────────────────────────────────────
-- 原 V009__domain_events_reclaim.sql
-- ─────────────────────────────────────────────────────────────

-- 回收滞留在"投递中"的事件记录所需的索引。
--
-- 领取之后、标记完成/失败之前进程崩溃，记录会卡在 status=1。原先没有任何机制把它改回
-- 待投递，于是这条记录永远不会被再次领取、也不会报错——事件静默永久丢失，而"不丢"
-- 正是选用事务性 outbox 的全部理由。
--
-- 现在 claimDue 额外捞 status=1 且 updated_at 超过可见性超时的行，本索引服务该条件。
CREATE INDEX IF NOT EXISTS idx_infra_domain_events_stale
    ON infra_domain_events (status, updated_at);

-- 历史数据修复：V008 时期总线恒传 createdAt=0，落库的时间戳全是 epoch 0。
-- 把它们对齐到当前时刻，否则这些行会被上面的滞留判定立刻误判为超时。
UPDATE infra_domain_events
SET created_at = (EXTRACT(EPOCH FROM NOW()) * 1000)::BIGINT,
    updated_at = (EXTRACT(EPOCH FROM NOW()) * 1000)::BIGINT
WHERE created_at = 0;


-- ─────────────────────────────────────────────────────────────
-- 原 V010__domain_events_claim_token.sql
-- ─────────────────────────────────────────────────────────────

-- 领取令牌（fencing token）。
--
-- 行锁只覆盖领取那一条语句，监听者执行期间早已释放。领取者 A 超时后被 B 重领，
-- A 迟到的 markDelivered / markFailed 若只按 id 匹配，会把 B 已经完成的记录改回
-- 待重试或终态失败。每次领取换新令牌、落定时校验，旧领取者的落定自动作废。
ALTER TABLE infra_domain_events ADD COLUMN IF NOT EXISTS claim_token VARCHAR(64);
