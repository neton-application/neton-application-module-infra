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
