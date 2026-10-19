package port

import neton.http.client.HttpClient
import neton.http.client.HttpClientError
import neton.http.client.OutboundAddressPolicy
import neton.http.client.TargetRejection

/**
 * 抓取**不可信 URL**（用户 / 编辑填的链接、采集到的页面与图片地址、重定向 `Location`）专用的出站客户端，由 infra 绑定。
 *
 * [client] 带 [policy]（neton-http `OutboundAddressPolicy`）：每条新连接解析一次主机名、全部地址都必须是公网地址、
 * 端口在放行列表里，然后只连校验过的地址——解析到内网的域名、`2130706433` 这类非规范 IP 写法、DNS 重绑定都在连接前被拒，
 * TLS 仍按原主机名做 SNI 与证书校验。被拒时请求抛 `HttpClientError.TargetRejected`，用 [rejectionCode] 转成机器码。
 *
 * 地址固定在配置里的出站调用（AI、支付、存储、推送）继续用应用绑定的共享 `HttpClient`，不走这里。
 * 调用方手动跟随重定向时，每一跳都用这个客户端发出，所以每一跳都会重新解析与校验。
 */
class UntrustedHttpClient(
    val policy: OutboundAddressPolicy,
    /** 按策略建客户端；应用没绑定 `HttpClientProvider` 时返回 null（调用方报 NOT_AVAILABLE）。 */
    private val factory: (OutboundAddressPolicy) -> HttpClient?,
) {
    private val created = lazy { factory(policy) }

    /** 带策略的客户端，第一次用时建（bootstrap 时应用可能还没绑定 provider）；null = 本部署没有出站能力。 */
    val client: HttpClient? get() = created.value

    /** 本对象拥有建出来的客户端，随应用停止关闭；没建过就什么都不做。 */
    suspend fun close() {
        if (created.isInitialized()) created.value?.close()
    }

    companion object {
        /** 客户端拒绝目标的原因 → 机器码。 */
        fun rejectionCode(reason: TargetRejection): MediaErrorCode = when (reason) {
            TargetRejection.SCHEME -> MediaErrorCode.URL_SCHEME_FORBIDDEN
            TargetRejection.PORT -> MediaErrorCode.URL_PORT_FORBIDDEN
            TargetRejection.ADDRESS -> MediaErrorCode.URL_TARGET_FORBIDDEN
            TargetRejection.RESOLVE -> MediaErrorCode.URL_RESOLVE_FAILED
        }

        /** [error] 是目标被拒时的机器码，否则 null（网络错误、超时、HTTP 状态码另行处理）。 */
        fun rejectionCode(error: HttpClientError): MediaErrorCode? =
            (error as? HttpClientError.TargetRejected)?.let { rejectionCode(it.reason) }
    }
}
