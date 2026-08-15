package event

import neton.core.config.ConfigLoader

/**
 * outbox 的运行参数，从 `infra.conf` 的 `[domain_events]` 段读取，缺省用代码默认值。
 *
 * ```toml
 * [domain_events]
 * batch_size = 100
 * max_attempts = 8
 * base_backoff_ms = 5000
 * max_backoff_ms = 600000
 * visibility_timeout_ms = 300000
 * retention_days = 7
 * backlog_warn_pending = 1000
 * backlog_warn_age_ms = 600000
 * ```
 *
 * 类型不对直接抛（`ConfigLoader.getInt` 的 fail-fast），不回退默认值。
 */
data class DomainEventSettings(
    val dispatch: DispatchConfig = DispatchConfig(),
    /** 已投递 / 已丢弃记录保留多少天后清理 */
    val retentionDays: Int = 7,
    /** 待投递深度超过该值即告警 */
    val backlogWarnPending: Long = 1_000,
    /** 最老待投递记录年龄超过该值即告警 */
    val backlogWarnAgeMillis: Long = 10 * 60 * 1000,
) {
    companion object {
        private const val SECTION = "domain_events"

        fun load(): DomainEventSettings = fromModuleConfig(ConfigLoader.loadModuleConfig(moduleName = "infra"))

        /** 从已加载的 infra 配置解析；`[domain_events]` 缺失时全用默认值。 */
        fun fromModuleConfig(conf: Map<String, Any?>?): DomainEventSettings {
            @Suppress("UNCHECKED_CAST")
            val section = ConfigLoader.getConfigValue(conf, SECTION) as? Map<String, Any?> ?: return DomainEventSettings()

            fun int(key: String, default: Int) =
                if (section.containsKey(key)) ConfigLoader.getInt(section, key) else default
            fun long(key: String, default: Long) =
                if (section.containsKey(key)) ConfigLoader.getInt(section, key).toLong() else default

            val d = DispatchConfig()
            return DomainEventSettings(
                dispatch = DispatchConfig(
                    batchSize = int("batch_size", d.batchSize),
                    maxAttempts = int("max_attempts", d.maxAttempts),
                    baseBackoffMillis = long("base_backoff_ms", d.baseBackoffMillis),
                    maxBackoffMillis = long("max_backoff_ms", d.maxBackoffMillis),
                    visibilityTimeoutMillis = long("visibility_timeout_ms", d.visibilityTimeoutMillis),
                ),
                retentionDays = int("retention_days", 7),
                backlogWarnPending = long("backlog_warn_pending", 1_000),
                backlogWarnAgeMillis = long("backlog_warn_age_ms", 10 * 60 * 1000),
            )
        }
    }
}
