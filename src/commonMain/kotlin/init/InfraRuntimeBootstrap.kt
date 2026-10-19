package init

import infra.DbAccessLogWriter
import infra.DbErrorLogWriter
import neton.core.component.NetonContext
import neton.core.interfaces.AccessLogWriter
import neton.core.interfaces.ErrorLogWriter
import neton.logging.LoggerFactory
import neton.storage.StorageOperator

import logic.*

// MANIFEST-P3: 手写 runtime bootstrap。FileLogic / JobLogic 已标 @Logic
// → 生成的 InfraLogicInitializer 装配; moduleId/dependsOn/migrations/路由 由 KSP manifest。
// 这里留: 事件编解码默认值 + 业务类型登记表 + FileUploadLogic / MediaLogic + 日志写入器。
object InfraRuntimeBootstrap {
    fun initialize(ctx: NetonContext) {
        val loggerFactory = ctx.get(LoggerFactory::class)

        // 配置定义注册中心：默认空表，application 层聚合各模块配置枚举后覆盖 bind。
        // 应用唯一用户态文件入口（spec MODULE_MEMBER_PROFILE_SPEC §4.2）。
        // storage 由 storage { } DSL 在 Main.kt 装配; policyRegistry inline 构造 → 非 @Logic。
        val storage = ctx.get(StorageOperator::class)
        // 持久化事件投递的落地实现（框架只定义端口，载体由应用层选）。表见 V008/V009。
        ctx.bindIfAbsent(
            neton.core.event.DomainEventStore::class,
            event.PostgresDomainEventStore(ctx.get(neton.database.api.DbContext::class)),
        )

        // 把持久化设施装进总线。总线由框架在启动最早期绑定（发布方那时已持有它），
        // 数据库要到这里才拿得到，所以分两步。应用层可以先绑自己的编解码；没绑就用按类登记的
        // 默认实现，各模块在自己的 bootstrap 里
        // `(ctx.get(DomainEventCodec::class) as? SerializerDomainEventCodec)?.register(...)`
        // 登记要可重试投递的事件。未登记的事件落库时报错，不会静默丢失。
        ctx.bindIfAbsent(
            neton.core.event.DomainEventCodec::class,
            neton.core.event.SerializerDomainEventCodec(),
        )
        val eventCodec = ctx.getOrNull(neton.core.event.DomainEventCodec::class)
        if (eventCodec != null) {
            ctx.get(neton.core.event.DomainEventBus::class)
                .attachStore(ctx.get(neton.core.event.DomainEventStore::class), eventCodec)
        }

        // 业务类型登记表：bindIfAbsent，宿主可预先绑定自己的；其它模块在各自 bootstrap 里
        // ctx.get(FileBusinessPolicyRegistry::class).register(...) 登记新类型（infra-media §3）。
        ctx.bindIfAbsent(FileBusinessPolicyRegistry::class, FileBusinessPolicyRegistry.default())
        val policyRegistry = ctx.get(FileBusinessPolicyRegistry::class)
        val db = ctx.get(neton.database.api.DbContext::class)
        // 文件公网前缀:conf [infra] file_public_base_url(如 https://h5.fflunp.cn/api),
        // 配置后下发绝对 URL,三端 <img>/Image 直接可加载。
        val infraConfig = neton.core.config.ConfigLoader.loadModuleConfig(moduleName = "infra")
        val filePublicBaseUrl = neton.core.config.ConfigLoader
            .getString(infraConfig, "file_public_base_url")?.trim()?.takeIf { it.isNotEmpty() }
        val fileUploadLogic = FileUploadLogic(
            log = loggerFactory.get("logic.app-file"),
            storage = storage,
            policyRegistry = policyRegistry,
            db = db,
            publicBaseUrl = filePublicBaseUrl,
        )
        ctx.bind(FileUploadLogic::class, fileUploadLogic)

        // 不可信 URL 出站客户端（转存、采集等抓取用户 / 外部内容给的地址）：每条连接解析、校验全部地址、只连校验过的地址。
        // 宿主可先绑定自己的；默认按 infra.conf [untrusted_fetch] 建，经应用绑定的 HttpClientProvider（模块不带引擎）。
        if (ctx.getOrNull(port.UntrustedHttpClient::class) == null) {
            val untrusted = UntrustedFetchSettings.load()
            val untrustedLog = loggerFactory.get("infra.untrusted-fetch")
            val policy = untrusted.policy()
            if (policy.isRelaxed) {
                // 逃生口打开 = 这些主机 / 网段可以被不可信 URL 打到。只该出现在本地开发。
                untrustedLog.warn(
                    "infra.untrusted-fetch.allowlist-enabled",
                    mapOf(
                        "allowedHosts" to policy.allowedHosts.joinToString(","),
                        "allowedNetworks" to policy.allowedNetworks.joinToString(","),
                        "allowedPorts" to policy.allowedPorts.sorted().joinToString(","),
                    ),
                )
            } else {
                untrustedLog.info("infra.untrusted-fetch.policy", mapOf("allowedPorts" to policy.allowedPorts.sorted().joinToString(",")))
            }
            val holder = port.UntrustedHttpClient(policy) { p ->
                ctx.getOrNull(neton.http.client.HttpClientProvider::class)?.create {
                    addressPolicy = p
                    connectMillis = untrusted.connectMillis
                }
            }
            ctx.bind(port.UntrustedHttpClient::class, holder)
            ctx.lifecycle.register("infra-untrusted-http-client", object : neton.core.component.NetonLifecycle {
                override suspend fun start() = Unit
                override suspend fun stop() = holder.close()
            })
        }
        val untrustedHttp = ctx.get(port.UntrustedHttpClient::class)

        // 媒体关联 / 访问契约（infra-media §6 / §7）。签名密钥从 JWT 密钥按用途派生，不新增配置。
        // MediaOwnerAccess、出站客户端在请求时读取：前者由 infra 之后装配的模块绑定。
        // 转存的地址来自用户 / 采集内容，只走不可信 URL 客户端。
        val mediaLogic = MediaLogic(
            log = loggerFactory.get("logic.media"),
            db = db,
            policyRegistry = policyRegistry,
            files = fileUploadLogic,
            signer = MediaUrlSigner(
                key = MediaUrlSigner.deriveKey(config.loadJwtRuntimeConfig(ctx).secretKey),
                nowSeconds = { kotlin.time.Clock.System.now().epochSeconds },
            ),
            ownerAccess = { ctx.getOrNull(port.MediaOwnerAccess::class) },
            httpClient = { untrustedHttp.client },
        )
        ctx.bind(MediaLogic::class, mediaLogic)
        ctx.bindIfAbsent(port.MediaService::class, mediaLogic)

        // IP 属地（spec content-platform-architecture §5.6）：全套件唯一的默认实现，宿主先绑定自己的即可替换。
        ctx.bindIfAbsent(contract.core.IpRegionResolver::class, GeoLiteIpRegionResolver.fromConfig())

        // 注册 API 日志写入器
        ctx.bind(AccessLogWriter::class, DbAccessLogWriter())
        ctx.bind(ErrorLogWriter::class, DbErrorLogWriter())

        // RP-7-B1: DB 驱动定时任务 —— infra_jobs 为运行期调度真源 + infra_job_logs 执行日志。
        // JobsComponent.prepare 消费 JobConfigSource（upsert 代码 @Job + DB enabled 覆盖）；
        // CoroutineJobScheduler 消费 JobExecutionListener（每次执行写日志）。
        // JobRegistry 由 JobsComponent 组件 init 绑定、模块 init 阶段被 @Job 片段填充 → 用 lazy provider。
        ctx.bind(
            neton.jobs.JobConfigSource::class,
            logic.DbBackedJobConfigSource(
                loggerFactory.get("jobs.config-source"),
                registryProvider = { ctx.get(neton.jobs.JobRegistry::class) },
            ),
        )
        ctx.bind(
            neton.jobs.JobExecutionListener::class,
            logic.JobLogListener(loggerFactory.get("jobs.log-listener")),
        )
    }
}
