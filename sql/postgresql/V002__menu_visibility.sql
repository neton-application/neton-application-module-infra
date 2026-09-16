-- 菜单「可见」与「可用」分离：隐藏的菜单仍是启用的路由、权限照旧，只是不出现在导航里
-- （详情页 / 下钻页有可见的父级入口时需要）。列的语义与读取方在 module-system
-- （Menu.kt / MenuLogic / PermissionLogic）。
--
-- 为什么 DDL 放在 infra 而不是 system：system_menus 这张表由本模块的 V001 baseline 创建
-- （历史包袱：infra 的 baseline 拥有 system_* 表的 DDL），而 infra 声明 dependsOn = ["system"]，
-- 迁移顺序是 system 先、infra 后。把这条 ALTER 放进 system 的迁移，在全新数据库上就会
-- 先于建表执行而报 42P01（首次由 harness 在隔离库上抓到）。改表的迁移必须跟着建表的模块走。
ALTER TABLE system_menus
    ADD COLUMN IF NOT EXISTS visible BOOLEAN NOT NULL DEFAULT TRUE;
