package logic

import neton.http.client.TargetRejection
import port.MediaErrorCode
import port.UntrustedHttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UntrustedHttpClientTest {

    @Test
    fun rejectionsMapToMachineCodes() {
        assertEquals(MediaErrorCode.URL_SCHEME_FORBIDDEN, UntrustedHttpClient.rejectionCode(TargetRejection.SCHEME))
        assertEquals(MediaErrorCode.URL_PORT_FORBIDDEN, UntrustedHttpClient.rejectionCode(TargetRejection.PORT))
        assertEquals(MediaErrorCode.URL_TARGET_FORBIDDEN, UntrustedHttpClient.rejectionCode(TargetRejection.ADDRESS))
        assertEquals(MediaErrorCode.URL_TARGET_FORBIDDEN, UntrustedHttpClient.rejectionCode(TargetRejection.PROXY))
        assertEquals(MediaErrorCode.URL_RESOLVE_FAILED, UntrustedHttpClient.rejectionCode(TargetRejection.RESOLVE))
    }

    @Test
    fun redirectLocationsResolveAgainstTheCurrentUrl() {
        val base = "https://cdn.example/img/a/b.png?sig=1#frag"
        assertEquals("https://other.example/x.png", MediaImportRules.resolveRedirect(base, "https://other.example/x.png"))
        assertEquals("https://edge.example/x.png", MediaImportRules.resolveRedirect(base, "//edge.example/x.png"))
        assertEquals("https://cdn.example/x.png", MediaImportRules.resolveRedirect(base, "/x.png"))
        assertEquals("https://cdn.example/img/a/c.png", MediaImportRules.resolveRedirect(base, "c.png"))
        assertEquals("https://cdn.example/img/a/b.png?v=2", MediaImportRules.resolveRedirect(base, "?v=2"))
        assertEquals("https://cdn.example/", MediaImportRules.resolveRedirect("https://cdn.example", "/"))
        // 非 http(s) 原样返回，由 checkUrl 拒绝
        assertEquals("file:///etc/passwd", MediaImportRules.resolveRedirect(base, "file:///etc/passwd"))
        assertEquals("javascript:alert(1)", MediaImportRules.resolveRedirect(base, "javascript:alert(1)"))
        assertEquals(MediaErrorCode.URL_SCHEME_FORBIDDEN, MediaImportRules.checkUrl("javascript:alert(1)").code)
        assertNull(MediaImportRules.resolveRedirect(base, "  "))
    }
}
