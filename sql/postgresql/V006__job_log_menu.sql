-- V006：补两条「接口存在、权限却授不出去」的按钮行
--
-- ── infra:job-log:query ──
-- 「定时任务」页右上角有一个「任务日志」链接，指向 standalone 的 /infra/job-log（它是详情页形态，
-- 刻意不进侧栏）。但这个权限没有任何菜单行，于是除超管外没人拿得到它 —— 点进去就是 403。
-- 所以这里补的是 **type=3 按钮行**而不是 type=2 页面行：RBAC spec §5.2 保证按钮不进菜单树，
-- 但 §6.1 会把它的 permission 收进权限集，正好对应「侧栏看不见、但点得动」这个真实形态。
-- 对本产品的意义：渠道测速与价源同步都是定时任务，它们跑没跑、跑挂没挂只能在这一页看。
--
-- ── infra:api-error-log:update ──
-- 错误日志页有「标记已处理」的接口（ApiErrorLogController 第 33 行），按钮行同样缺失。
INSERT INTO system_menus (name, permission, type, parent_id, path, component, icon, sort, status, created_at, updated_at)
SELECT v.name, v.permission, 3,
       (SELECT p.id FROM system_menus p WHERE p.permission = v.parent_permission AND p.type = 2),
       '', NULL, '', v.sort, 1,
       (extract(epoch from now()) * 1000)::bigint, (extract(epoch from now()) * 1000)::bigint
  FROM (VALUES
    ('任务日志',     'infra:job-log:query',        'infra:job:query',           5),
    ('标记已处理',   'infra:api-error-log:update', 'infra:api-error-log:query', 1)
  ) AS v(name, permission, parent_permission, sort)
 WHERE EXISTS (SELECT 1 FROM system_menus p WHERE p.permission = v.parent_permission AND p.type = 2)
   AND NOT EXISTS (SELECT 1 FROM system_menus m WHERE m.permission = v.permission);
