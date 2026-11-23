-- These rows previously did not drive uploads. Require explicit testing/activation
-- rather than silently opting existing (often empty) configurations into routing.
UPDATE infra_file_configs SET master = 0 WHERE master = 1;

CREATE UNIQUE INDEX infra_file_configs_one_master ON infra_file_configs (master) WHERE master = 1;
