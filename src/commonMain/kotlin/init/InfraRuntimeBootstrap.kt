package init

import infra.DbAccessLogWriter
import infra.DbErrorLogWriter
import neton.core.component.NetonContext
import neton.core.interfaces.AccessLogWriter
import neton.core.interfaces.ErrorLogWriter
import neton.logging.LoggerFactory
import neton.storage.StorageOperator

import logic.*

// MANIFEST-P3: 手写 runtime bootstrap。ConfigLogic / FileLogic / JobLogic 已标 @Logic
// → 生成的 InfraLogicInitializer 装配; moduleId/dependsOn/migrations/路由 由 KSP manifest。
// 这里留: FileUploadLogic (storage + inline policyRegistry) + 日志写入器。
object InfraRuntimeBootstrap {
    fun initialize(ctx: NetonContext) {
        val loggerFactory = ctx.get(LoggerFactory::class)
        // 应用唯一用户态文件入口（spec MODULE_MEMBER_PROFILE_SPEC §4.2）。
        // storage 由 storage { } DSL 在 Main.kt 装配; policyRegistry inline 构造 → 非 @Logic。
        val storage = ctx.get(StorageOperator::class)
        val policyRegistry = FileBusinessPolicyRegistry.default()
        ctx.bind(FileBusinessPolicyRegistry::class, policyRegistry)
        // 文件公网前缀:conf [infra] file_public_base_url(如 https://h5.fflunp.cn/api),
        // 配置后下发绝对 URL,三端 <img>/Image 直接可加载。
        val infraConfig = neton.core.config.ConfigLoader.loadModuleConfig(moduleName = "infra")
        val filePublicBaseUrl = neton.core.config.ConfigLoader
            .getString(infraConfig, "file_public_base_url")?.trim()?.takeIf { it.isNotEmpty() }
        ctx.bind(
            FileUploadLogic::class,
            FileUploadLogic(
                log = loggerFactory.get("logic.app-file"),
                storage = storage,
                policyRegistry = policyRegistry,
                publicBaseUrl = filePublicBaseUrl,
            ),
        )

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
