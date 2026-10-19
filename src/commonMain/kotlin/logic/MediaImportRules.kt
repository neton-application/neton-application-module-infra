package logic

import port.MediaErrorCode

/**
 * 服务端转存的纯规则（infra-media §4.3）：URL 校验、MIME 嗅探、文件名推导。
 */
object MediaImportRules {

    data class UrlCheck(val ok: Boolean, val code: MediaErrorCode? = null, val message: String = "")

    /**
     * 不可信 URL 的**语法**检查：只允许 http / https，拒绝带用户信息、空白与超长的地址。
     *
     * 目标地址（本机 / 内网 / 保留段、非规范 IP 写法、端口）**不在这里判断**：只看字面主机名挡不住解析到内网的域名，
     * 也挡不住校验与连接之间被换掉的解析结果。那由 [port.UntrustedHttpClient] 的客户端在每条连接上解析、
     * 校验全部地址、只连校验过的地址来保证（neton-http `OutboundAddressPolicy`）。
     */
    fun checkUrl(url: String): UrlCheck {
        val trimmed = url.trim()
        if (trimmed.isEmpty() || trimmed.length > 2048) return fail(MediaErrorCode.INVALID_URL, "url is empty or too long")
        val schemeEnd = trimmed.indexOf("://")
        if (schemeEnd <= 0) {
            // file:/etc/passwd、javascript:… 这类没有 // 的写法同样是不允许的 scheme
            val colon = trimmed.indexOf(':')
            if (colon > 0 && trimmed.substring(0, colon).all { it.isLetterOrDigit() || it in "+.-" }) {
                return fail(MediaErrorCode.URL_SCHEME_FORBIDDEN, "only http/https is allowed")
            }
            return fail(MediaErrorCode.INVALID_URL, "url has no scheme")
        }
        val scheme = trimmed.substring(0, schemeEnd).lowercase()
        if (scheme != "http" && scheme != "https") {
            return fail(MediaErrorCode.URL_SCHEME_FORBIDDEN, "only http/https is allowed")
        }
        val rest = trimmed.substring(schemeEnd + 3)
        val authority = rest.substringBefore('/').substringBefore('?').substringBefore('#')
        if (authority.isEmpty()) return fail(MediaErrorCode.INVALID_URL, "url has no host")
        if ('@' in authority) return fail(MediaErrorCode.INVALID_URL, "userinfo in url is not allowed")
        if (trimmed.any { it.isWhitespace() || it.code < 0x20 }) return fail(MediaErrorCode.INVALID_URL, "url contains whitespace")
        val host = hostOf(authority).trimEnd('.')
        if (host.isEmpty()) return fail(MediaErrorCode.INVALID_URL, "url has no host")
        return UrlCheck(true)
    }

    private fun hostOf(authority: String): String =
        if (authority.startsWith("[")) authority.substringAfter('[').substringBefore(']')
        else authority.substringBefore(':')

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

    private fun fail(code: MediaErrorCode, message: String) = UrlCheck(false, code, message)
}
