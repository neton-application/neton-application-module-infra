-- V008：后台媒体上传与读取（infra-media §4.1「后台上传同理」、§9）
--
-- ── admin_uploader_id ──
-- 后台账号（system_users）与应用账号（owner_uid 所指）是两套 id 空间。后台上传若把管理员 id
-- 写进 owner_uid，应用侧同号账号就会被当成上传者（能读 OWNER / DELEGATED 文件、能 attach 临时文件）。
-- 所以后台上传的行 owner_uid 留空，上传者记在这一列；应用侧的「本人」判断只看 owner_uid，不受影响。
ALTER TABLE infra_files ADD COLUMN IF NOT EXISTS admin_uploader_id BIGINT;

-- 后台托管上传的去重路径（同上传者、同类型、同内容）。
CREATE INDEX IF NOT EXISTS idx_infra_files_admin_uploader
    ON infra_files(admin_uploader_id, business_type, sha256) WHERE admin_uploader_id IS NOT NULL;

COMMENT ON COLUMN infra_files.admin_uploader_id IS '后台上传者（system_users.id）；有值时 owner_uid 为空';

-- ── 按钮权限 ──
-- POST /admin/infra/media/upload（multipart 上传）与 GET /admin/infra/media/get/{fileId}（读取任意未删除文件）。
-- 挂在「文件管理」页下的 type=3 按钮行：不进菜单树，但进权限集（RBAC spec §5.2 / §6.1）。
-- 内容编辑、评论管理等后台页面需要上传 / 预览媒体的角色，要同时授予这两个按钮。
INSERT INTO system_menus (name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at)
SELECT v.name, v.permission, 3,
       (SELECT p.id FROM system_menus p WHERE p.permission = v.parent_permission AND p.type = 2),
       '', NULL, '', v.sort, 1,
       (extract(epoch from now()) * 1000)::bigint, (extract(epoch from now()) * 1000)::bigint
  FROM (VALUES
    ('媒体上传', 'infra:media:upload', 'infra:file:query', 4),
    ('媒体预览', 'infra:media:query',  'infra:file:query', 5)
  ) AS v(name, permission, parent_permission, sort)
 WHERE EXISTS (SELECT 1 FROM system_menus p WHERE p.permission = v.parent_permission AND p.type = 2)
   AND NOT EXISTS (SELECT 1 FROM system_menus m WHERE m.permission = v.permission);

INSERT INTO system_role_menus (role_id, menu_id, created_at)
SELECT r.id, m.id, (extract(epoch from now()) * 1000)::bigint
  FROM system_roles r
  JOIN system_menus m ON m.permission IN ('infra:media:upload', 'infra:media:query') AND m.type = 3
 WHERE r.code = 'super_admin'
   AND NOT EXISTS (SELECT 1 FROM system_role_menus rm WHERE rm.role_id = r.id AND rm.menu_id = m.id);
