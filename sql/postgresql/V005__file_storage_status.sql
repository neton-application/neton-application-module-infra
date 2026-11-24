ALTER TABLE infra_file_configs
    ADD COLUMN status SMALLINT NOT NULL DEFAULT 1,
    ADD CONSTRAINT ck_infra_file_configs_status CHECK (status IN (0, 1)),
    ADD CONSTRAINT ck_infra_file_configs_enabled_master CHECK (master <> 1 OR status = 1);
