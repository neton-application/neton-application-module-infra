package logic

import port.MediaErrorCode

/**
 * 服务端转存的纯规则（infra-media §4.3）：URL 校验、MIME 嗅探、文件名推导。
 */
object MediaImportRules {

    data class UrlCheck(val ok: Boolean, val code: MediaErrorCode? = null, val message: String = "")

    /**
     * 只允许 http / https；拒绝带用户信息的地址和明显指向本机 / 内网的字面地址。
     *
     * 🔴 这里只能挡住**字面**地址；域名解析到内网（DNS rebinding）和上游重定向到内网需要
     * 出站代理或网络层出口策略兜底，不在本函数能力范围内。
     */
    fun checkUrl(url: String): UrlCheck {
        val trimmed = url.trim()
        if (trimmed.isEmpty() || trimmed.length > 2048) return fail(MediaErrorCode.INVALID_URL, "url is empty or too long")
        val schemeEnd = trimmed.indexOf("://")
        if (schemeEnd <= 0) return fail(MediaErrorCode.INVALID_URL, "url has no scheme")
        val scheme = trimmed.substring(0, schemeEnd).lowercase()
        if (scheme != "http" && scheme != "https") {
            return fail(MediaErrorCode.UNSUPPORTED_SCHEME, "only http/https is allowed")
        }
        val rest = trimmed.substring(schemeEnd + 3)
        val authority = rest.substringBefore('/').substringBefore('?').substringBefore('#')
        if (authority.isEmpty()) return fail(MediaErrorCode.INVALID_URL, "url has no host")
        if ('@' in authority) return fail(MediaErrorCode.INVALID_URL, "userinfo in url is not allowed")
        if (trimmed.any { it.isWhitespace() || it.code < 0x20 }) return fail(MediaErrorCode.INVALID_URL, "url contains whitespace")
        val host = hostOf(authority).lowercase().trimEnd('.')
        if (host.isEmpty()) return fail(MediaErrorCode.INVALID_URL, "url has no host")
        if (isForbiddenHost(host)) return fail(MediaErrorCode.FORBIDDEN_HOST, "host is not allowed")
        return UrlCheck(true)
    }

    private fun hostOf(authority: String): String =
        if (authority.startsWith("[")) authority.substringAfter('[').substringBefore(']')
        else authority.substringBefore(':')

    fun isForbiddenHost(host: String): Boolean {
        if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local") || host.endsWith(".internal")) {
            return true
        }
        // IPv6 字面量：回环、未指定、链路本地、唯一本地、IPv4 映射
        if (':' in host) {
            val h = host.lowercase()
            return h == "::1" || h == "::" || h.startsWith("fe80:") || h.startsWith("fc") || h.startsWith("fd") ||
                h.startsWith("::ffff:")
        }
        val parts = host.split('.')
        if (parts.size == 4 && parts.all { p -> p.isNotEmpty() && p.all { it.isDigit() } }) {
            // 带前导零的写法可能被解析成八进制（0177.0.0.1 = 127.0.0.1），直接拒绝
            if (parts.any { it.length > 1 && it.startsWith('0') }) return true
            val o = parts.map { it.toIntOrNull() ?: return true }
            if (o.any { it > 255 }) return true
            return o[0] == 0 || o[0] == 10 || o[0] == 127 ||
                (o[0] == 169 && o[1] == 254) ||
                (o[0] == 172 && o[1] in 16..31) ||
                (o[0] == 192 && o[1] == 168) ||
                (o[0] == 100 && o[1] in 64..127) ||
                o[0] >= 224
        }
        // 纯数字 / 十六进制写法的 IPv4（如 2130706433、0x7f000001）一律拒绝
        if (host.all { it.isDigit() } || HEX_HOST.matches(host)) return true
        return false
    }

    /** 按文件头识别常见类型；识别不了返回 null。 */
    fun sniffMime(bytes: ByteArray): String? {
        fun at(i: Int) = if (i < bytes.size) bytes[i].toInt() and 0xFF else -1
        fun ascii(offset: Int, s: String) = s.indices.all { at(offset + it) == s[it].code }
        return when {
            at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF -> "image/jpeg"
            at(0) == 0x89 && ascii(1, "PNG") && at(4) == 0x0D && at(5) == 0x0A && at(6) == 0x1A && at(7) == 0x0A -> "image/png"
            ascii(0, "GIF87a") || ascii(0, "GIF89a") -> "image/gif"
            ascii(0, "RIFF") && ascii(8, "WEBP") -> "image/webp"
            ascii(0, "%PDF-") -> "application/pdf"
            ascii(4, "ftyp") -> "video/mp4"
            else -> null
        }
    }

    /**
     * 最终 MIME：能嗅探出来就以内容为准；嗅探不出时，声明为图片 / PDF / 视频的一律拒绝
     * （这些类型都有固定文件头，嗅探不出说明内容与声明不符），其它类型才信任响应头。
     */
    fun decideMime(sniffed: String?, declared: String?): String? {
        if (sniffed != null) return sniffed
        val d = declared?.substringBefore(';')?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        if (d.startsWith("image/") || d.startsWith("video/") || d == "application/pdf") return null
        return d
    }

    /** 从 URL 路径取文件名，只保留安全字符。 */
    fun filenameOf(url: String): String {
        val path = url.substringAfter("://").substringAfter('/', "").substringBefore('?').substringBefore('#')
        val last = path.substringAfterLast('/')
        val safe = last.filter { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }.take(100)
        return safe.ifEmpty { "import" }
    }

    private val HEX_HOST = Regex("^0x[0-9a-f]+$")

    private fun fail(code: MediaErrorCode, message: String) = UrlCheck(false, code, message)
}
