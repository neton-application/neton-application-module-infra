-- V004：清掉四个指向不存在接口的按钮权限
--
-- baseline 在「系统设置」页下种了 配置查询 / 配置新增 / 配置修改 / 配置删除 四个按钮，
-- 权限串是 infra:config:query / create / update / delete。但 module-infra 里**没有任何**
-- config 控制器（/infra/config 不存在），真正提供系统设置的是 module-system 的
-- /system/setting（权限 system:setting:query / update）。
--
-- 后果是死授权：运营把这四个勾给某个角色，角色的权限集里多了四个串，却没有任何接口认它们；
-- 而「系统设置」页真正需要的 system:setting:update 反而要单独再勾一次。
-- 停用而不删除的理由同 ENGINEERING_RULES §6.3：菜单是运营数据，可在「菜单管理」里看见。
UPDATE system_menus
   SET status = 0, updated_at = (extract(epoch from now()) * 1000)::bigint
 WHERE permission LIKE 'infra:config:%';
