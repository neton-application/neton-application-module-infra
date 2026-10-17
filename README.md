# neton-application-module-infra

Neton canonical 基础设施模块：配置、文件上传、定时任务、API 日志、Redis 监控。

- 外部 canonical 模块，由 `neton-application` 及各发行版以源码子项目聚合，也可独立编译。
- moduleId：`infra`；**合并持有 system+infra 的迁移**（`sql/postgresql/`，DB-MIG-7A）。
- IP 属地：实现套件契约 `contract.core.IpRegionResolver`（`logic.GeoLiteIpRegionResolver`，本地 GeoLite2 库，路径见 `config/geolite.conf` 的 `path`，首次查询时加载），bootstrap 里 `bindIfAbsent`；account、comment 等经端口使用，宿主可先绑定自己的实现替换。
- 依赖 `module-system`、`application-contract`、`geolite4k`。规范见 neton-application `docs/ENGINEERING_RULES.md`。
