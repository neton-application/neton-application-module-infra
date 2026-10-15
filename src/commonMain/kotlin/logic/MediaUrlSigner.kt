package logic

import neton.security.crypto.HmacSha256

/**
 * 非公开媒体的短期签名地址（infra-media §7.2）。
 *
 * 地址形如 `/app/infra/file/get/{fileId}?exp=<epoch 秒>&sig=<hex>`，
 * `sig = HMAC-SHA256(key, "fileId|exp")`。签名只证明「签发时访问者可读」，到期即失效；
 * 引用对象变为不可见后 [MediaLogic.resolve] 不再签发新地址。
 *
 * 密钥由 JWT 密钥按用途派生（[deriveKey]），不新增配置项，也不与令牌签名共用同一把密钥。
 */
class MediaUrlSigner(
    private val key: ByteArray,
    val ttlSeconds: Long = DEFAULT_TTL_SECONDS,
    private val nowSeconds: () -> Long,
) {
    init {
        require(key.size >= 16) { "media url signing key too short" }
        require(ttlSeconds > 0) { "ttlSeconds must be > 0" }
    }

    data class Signature(val exp: Long, val sig: String)

    fun sign(fileId: String, ttl: Long = ttlSeconds): Signature {
        val exp = nowSeconds() + ttl
        return Signature(exp, HmacSha256.signHex(key, signingInput(fileId, exp)))
    }

    /** 在相对 / 绝对地址后拼上签名参数。 */
    fun signedUrl(baseUrl: String, fileId: String): Pair<String, Long> {
        val s = sign(fileId)
        val sep = if ('?' in baseUrl) '&' else '?'
        return "$baseUrl${sep}exp=${s.exp}&sig=${s.sig}" to s.exp
    }

    /** 未过期且签名匹配（常数时间比较）。参数缺失、格式不对一律 false。 */
    fun verify(fileId: String, exp: Long?, sig: String?): Boolean {
        if (exp == null || sig.isNullOrBlank()) return false
        if (sig.length != SIG_HEX_LENGTH) return false
        if (exp < nowSeconds()) return false
        return HmacSha256.verifyHex(key, signingInput(fileId, exp), sig)
    }

    /** 签名剩余有效秒数（用于 Cache-Control）。 */
    fun remainingSeconds(exp: Long): Long = (exp - nowSeconds()).coerceAtLeast(0)

    private fun signingInput(fileId: String, exp: Long): ByteArray = "$fileId|$exp".encodeToByteArray()

    companion object {
        const val DEFAULT_TTL_SECONDS: Long = 600
        const val KEY_PURPOSE: String = "neton.infra.media-url.v1"
        private const val SIG_HEX_LENGTH = 64

        /** 与 module-system 的 `loginThrottleKey` 同一做法：HMAC(JWT 密钥, 用途标签)。 */
        fun deriveKey(jwtSecret: String): ByteArray =
            HmacSha256.sign(jwtSecret.encodeToByteArray(), KEY_PURPOSE.encodeToByteArray())
    }
}
