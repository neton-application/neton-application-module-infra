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
