# neton-application-module-infra

Neton canonical 基础设施模块：配置、文件上传、定时任务、API 日志、Redis 监控。

## 文件存储配置

后台文件配置现在驱动真实上传。须使用包含 `StorageOperatorFactory` 的 Neton 源码依赖，并同步部署 front-infra。

升级先执行 V003 迁移：撤销旧 master 标记（此前没有驱动上传），保持原启动默认源；管理员补齐参数、测试并明确激活后才切换新上传。历史文件仍按原配置读取。引用配置不可改址/删除；换目录或桶应新建配置，不会自动迁移对象。

`config/infra.conf` 部署白名单：

```toml
[file_storage]
local_root = "/srv/neton/files"
allowed_endpoints = ["https://s3.example.com"]
```

后台本地目录填相对子目录。S3 只填 `NETON_STORAGE_*` 凭证环境变量名，明文密钥由部署环境注入，不入库、不回显。OSS/COS 仅在提供 S3 兼容协议时以 S3 配置使用，不宣称原生驱动。全局 CDN 只作用于启动默认源，后台源使用自己的公开地址或鉴权中转。

测试改为 `POST /admin/infra/file-config/test/{id}`，需 update 权限，实际执行随机对象写/读/删除；不能以 GET 发起写测试。旧预签名接口明确拒绝直传，使用标准媒体上传接口。

验收：`./gradlew macosArm64Test`；`PG_BIN=/path/to/postgresql/bin bash scripts/test-file-storage-postgres.sh` 启动仅本机临时数据库和文件目录，结束自动清理。完整设计见 neton-docs `application/infra-file-config.md`。

- 外部 canonical 模块，由 `neton-application` 及各发行版以源码子项目聚合，也可独立编译。
- moduleId：`infra`；**合并持有 system+infra 的迁移**（`sql/postgresql/`，DB-MIG-7A）。
- IP 属地：实现套件契约 `contract.core.IpRegionResolver`（`logic.GeoLiteIpRegionResolver`，本地 GeoLite2 库，路径见 `config/geolite.conf` 的 `path`，首次查询时加载），bootstrap 里 `bindIfAbsent`；account、comment 等经端口使用，宿主可先绑定自己的实现替换。
- 依赖 `module-system`、`application-contract`、`geolite4k`。规范见 neton-application `docs/ENGINEERING_RULES.md`。
