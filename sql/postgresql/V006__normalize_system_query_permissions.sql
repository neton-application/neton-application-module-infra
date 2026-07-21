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
  );

SELECT setval(
    pg_get_serial_sequence('system_menus', 'id'),
    (SELECT MAX(id) FROM system_menus)
);
