package logic

import port.MediaErrorCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 短期签名地址（infra-media §7.2）与服务端转存规则（§4.3）。 */
class MediaUrlSignerTest {

    private var clock = 1_000_000L
    private val key = MediaUrlSigner.deriveKey("x".repeat(40))
    private val signer = MediaUrlSigner(key, ttlSeconds = 600, nowSeconds = { clock })

    @Test
    fun signAndVerifyRoundTrip() {
        val s = signer.sign("file_abc")
        assertEquals(clock + 600, s.exp)
        assertEquals(64, s.sig.length)
        assertTrue(signer.verify("file_abc", s.exp, s.sig))
        assertEquals(600, signer.remainingSeconds(s.exp))
    }

    @Test
    fun rejectsTamperingExpiryAndOtherFiles() {
        val s = signer.sign("file_abc")
        assertFalse(signer.verify("file_abd", s.exp, s.sig), "换文件")
        assertFalse(signer.verify("file_abc", s.exp + 1, s.sig), "改过期时间")
        assertFalse(signer.verify("file_abc", s.exp, s.sig.reversed()), "改签名")
        assertFalse(signer.verify("file_abc", null, s.sig))
        assertFalse(signer.verify("file_abc", s.exp, null))
        assertFalse(signer.verify("file_abc", s.exp, "zz"))
        clock += 601
        assertFalse(signer.verify("file_abc", s.exp, s.sig), "过期")
    }

    @Test
    fun differentSecretsGiveDifferentSignatures() {
        val other = MediaUrlSigner(MediaUrlSigner.deriveKey("y".repeat(40)), nowSeconds = { clock })
        val s = signer.sign("file_abc")
        assertFalse(other.verify("file_abc", s.exp, s.sig))
        // 用途派生：不等于直接拿 JWT 密钥签
        assertFalse(key.contentEquals("x".repeat(40).encodeToByteArray()))
    }

    @Test
    fun signedUrlAppendsQuery() {
        val (url, exp) = signer.signedUrl("/app/infra/file/get/file_abc", "file_abc")
        assertTrue(url.startsWith("/app/infra/file/get/file_abc?exp=$exp&sig="), url)
        val (url2, _) = signer.signedUrl("https://h/x?a=1", "file_abc")
        assertTrue(url2.startsWith("https://h/x?a=1&exp="), url2)
    }

    // ---- 转存规则 ----

    @Test
    fun urlChecks() {
        assertTrue(MediaImportRules.checkUrl("https://example.com/a.png").ok)
        assertTrue(MediaImportRules.checkUrl("HTTP://example.com:8080/a.png?x=1").ok)
        assertEquals(MediaErrorCode.URL_SCHEME_FORBIDDEN, MediaImportRules.checkUrl("ftp://example.com/a").code)
        assertEquals(MediaErrorCode.URL_SCHEME_FORBIDDEN, MediaImportRules.checkUrl("file:///etc/passwd").code)
        assertEquals(MediaErrorCode.URL_SCHEME_FORBIDDEN, MediaImportRules.checkUrl("javascript:alert(1)").code)
        assertEquals(MediaErrorCode.INVALID_URL, MediaImportRules.checkUrl("example.com/a").code)
        assertEquals(MediaErrorCode.INVALID_URL, MediaImportRules.checkUrl("https://user:pw@example.com/").code)
        assertEquals(MediaErrorCode.INVALID_URL, MediaImportRules.checkUrl("https://exa mple.com/").code)
        assertEquals(MediaErrorCode.INVALID_URL, MediaImportRules.checkUrl("https:///a").code)
        // 目标地址（内网、回环、非规范 IP 写法、端口）不在语法检查里判断，由不可信 URL 客户端按解析结果拒绝
        assertTrue(MediaImportRules.checkUrl("http://127.0.0.1/").ok)
    }

    @Test
    fun mimeSniffing() {
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A, 0)
        val jpg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
        val webp = "RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".encodeToByteArray()
        assertEquals("image/png", MediaImportRules.sniffMime(png))
        assertEquals("image/jpeg", MediaImportRules.sniffMime(jpg))
        assertEquals("image/webp", MediaImportRules.sniffMime(webp))
        assertEquals("image/gif", MediaImportRules.sniffMime("GIF89a....".encodeToByteArray()))
        assertNull(MediaImportRules.sniffMime("<html>".encodeToByteArray()))

        // 内容优先于声明
        assertEquals("image/png", MediaImportRules.decideMime("image/png", "image/jpeg"))
        // 声明是图片但内容不是 → 拒绝
        assertNull(MediaImportRules.decideMime(null, "image/png"))
        assertEquals("text/plain", MediaImportRules.decideMime(null, "text/plain; charset=utf-8"))
        assertNull(MediaImportRules.decideMime(null, null))
    }

    @Test
    fun filenameFromUrl() {
        assertEquals("a.png", MediaImportRules.filenameOf("https://x.com/p/a.png?x=1"))
        assertEquals("import", MediaImportRules.filenameOf("https://x.com/"))
    }

    @Test
    fun inClausePlaceholders() {
        val (clause, params) = MediaLogic.inClause("f", listOf("a", "b"))
        assertEquals(":f0, :f1", clause)
        assertEquals(mapOf<String, Any?>("f0" to "a", "f1" to "b"), params)
    }
}
