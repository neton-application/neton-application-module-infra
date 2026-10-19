package port

import neton.http.client.HttpClient
import neton.http.client.HttpClientError
import neton.http.client.TargetRejection

/**
 * 抓取**不可信 URL**（用户 / 编辑填的链接、采集到的页面与图片地址、重定向 `Location`）专用的共享出站客户端，由 infra 绑定。
 *
 * 客户端由全进程唯一的 `GuardedHttpClientProvider`（neton-http，module-system 按 application.conf `[egress]` 绑定）建：
 * 每条新连接解析一次主机名、全部地址都必须是公网地址（或运维放行的）、端口在放行列表里，然后只连校验过的地址——
 * 解析到内网的域名、`2130706433` 这类非规范 IP 写法、DNS 重绑定都在连接前被拒，TLS 仍按原主机名做 SNI 与证书校验。
 * 被拒时请求抛 `HttpClientError.TargetRejected`，用 [rejectionCode] 转成机器码。infra 不再有自己的放行列表。
 *
 * 调用方手动跟随重定向时，每一跳都用这个客户端发出，所以每一跳都会重新解析与校验。
 */
class UntrustedHttpClient(
    /** 建客户端；本部署没有出站能力时返回 null（调用方报 NOT_AVAILABLE）。 */
    private val factory: () -> HttpClient?,
) {
    private val created = lazy { factory() }

    /** 受控客户端，第一次用时建；null = 本部署没有出站能力。 */
    val client: HttpClient? get() = created.value

    /** 本对象拥有建出来的客户端，随应用停止关闭；没建过就什么都不做。 */
    suspend fun close() {
        if (created.isInitialized()) created.value?.close()
    }

    companion object {
        /** 客户端拒绝目标的原因 → 机器码。这个客户端不配代理，PROXY 只会来自宿主自绑的客户端，按目标被拒处理。 */
        fun rejectionCode(reason: TargetRejection): MediaErrorCode = when (reason) {
            TargetRejection.SCHEME -> MediaErrorCode.URL_SCHEME_FORBIDDEN
            TargetRejection.PORT -> MediaErrorCode.URL_PORT_FORBIDDEN
            TargetRejection.ADDRESS, TargetRejection.PROXY -> MediaErrorCode.URL_TARGET_FORBIDDEN
            TargetRejection.RESOLVE -> MediaErrorCode.URL_RESOLVE_FAILED
        }

        /** [error] 是目标被拒时的机器码，否则 null（网络错误、超时、HTTP 状态码另行处理）。 */
        fun rejectionCode(error: HttpClientError): MediaErrorCode? =
            (error as? HttpClientError.TargetRejected)?.let { rejectionCode(it.reason) }
    }
}
