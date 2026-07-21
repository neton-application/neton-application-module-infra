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
