# neton-application-module-infra

Neton canonical 基础设施模块：配置、文件上传、定时任务、API 日志、Redis 监控。

- 外部 canonical 模块，由 `neton-application` 及各发行版以源码子项目聚合，也可独立编译。
- moduleId：`infra`；**合并持有 system+infra 的迁移**（`sql/postgresql/`，DB-MIG-7A）。
- 依赖 `module-system`。规范见 neton-application `docs/ENGINEERING_RULES.md`。
