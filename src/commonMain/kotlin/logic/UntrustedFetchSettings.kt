package logic

import neton.core.config.ConfigLoader
import neton.http.client.IpNetwork
import neton.http.client.OutboundAddressPolicy

/**
 * `infra.conf` 的 `[untrusted_fetch]`：不可信 URL 出站客户端（[port.UntrustedHttpClient]）的地址策略。
 *
 * ```toml
 * [untrusted_fetch]
 * allowed_ports = "80,443"          # 默认 80,443
 * # 以下两项是本地开发的逃生口，默认为空；任一非空时启动日志会打 WARN。
 * allowed_hosts = "hn-stub.itwind.test"      # 主机名（或 IP 字面量）精确匹配：不论解析到哪、不论端口都放行
 * allowed_networks = "198.18.0.0/15"         # CIDR：这些网段的地址放行（端口仍按 allowed_ports）
 * connect_ms = 5000
 * ```
 *
 * 也可用环境变量覆盖：`NETON_INFRA__UNTRUSTED_FETCH__ALLOWED_HOSTS=...`。列表用逗号分隔（框架的 TOML 解析不支持数组）。
 * 写错（端口不是数字、CIDR 不合法）直接抛，不静默回退默认值。
 */
data class UntrustedFetchSettings(
    val allowedPorts: Set<Int> = OutboundAddressPolicy.DEFAULT_PORTS,
    val allowedHosts: Set<String> = emptySet(),
    val allowedNetworks: List<IpNetwork> = emptyList(),
    val connectMillis: Long = 5_000,
) {
    fun policy(): OutboundAddressPolicy = OutboundAddressPolicy(allowedPorts, allowedHosts, allowedNetworks)

    companion object {
        private const val SECTION = "untrusted_fetch"

        fun load(): UntrustedFetchSettings = fromModuleConfig(ConfigLoader.loadModuleConfig(moduleName = "infra"))

        fun fromModuleConfig(conf: Map<String, Any?>?): UntrustedFetchSettings {
            @Suppress("UNCHECKED_CAST")
            val section = ConfigLoader.getConfigValue(conf, SECTION) as? Map<String, Any?> ?: return UntrustedFetchSettings()
            fun list(key: String): List<String> =
                section[key]?.toString()?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
            val ports = if (section.containsKey("allowed_ports")) {
                list("allowed_ports").map { p ->
                    p.toIntOrNull()?.takeIf { it in 1..65535 }
                        ?: throw IllegalArgumentException("infra.untrusted_fetch.allowed_ports: '$p' is not a port")
                }.toSet()
            } else OutboundAddressPolicy.DEFAULT_PORTS
            val networks = list("allowed_networks").map { n ->
                try {
                    IpNetwork.parse(n)
                } catch (e: IllegalArgumentException) {
                    throw IllegalArgumentException("infra.untrusted_fetch.allowed_networks: '$n' is not a CIDR block", e)
                }
            }
            val connect = if (section.containsKey("connect_ms")) ConfigLoader.getInt(section, "connect_ms").toLong() else 5_000L
            return UntrustedFetchSettings(ports, list("allowed_hosts").toSet(), networks, connect)
        }
    }
}
