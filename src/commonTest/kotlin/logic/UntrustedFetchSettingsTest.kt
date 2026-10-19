package logic

import neton.http.client.IpNetwork
import neton.http.client.OutboundAddressPolicy
import neton.http.client.TargetRejection
import port.MediaErrorCode
import port.UntrustedHttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UntrustedFetchSettingsTest {

    @Test
    fun defaultsArePublicInternetOnPorts80And443() {
        for (conf in listOf(null, emptyMap(), mapOf("untrusted_fetch" to emptyMap<String, Any?>()))) {
            val s = UntrustedFetchSettings.fromModuleConfig(conf)
            assertEquals(setOf(80, 443), s.allowedPorts)
            assertFalse(s.policy().isRelaxed)
        }
    }

    @Test
    fun allowlistsParseFromCommaSeparatedValues() {
        val s = UntrustedFetchSettings.fromModuleConfig(
            mapOf(
                "untrusted_fetch" to mapOf(
                    "allowed_ports" to "80, 443,8443",
                    "allowed_hosts" to "hn-stub.itwind.test, Other.Test.",
                    "allowed_networks" to "198.18.0.0/15",
                    "connect_ms" to 2000,
                ),
            ),
        )
        assertEquals(setOf(80, 443, 8443), s.allowedPorts)
        assertEquals(listOf(IpNetwork.parse("198.18.0.0/15")), s.allowedNetworks)
        assertEquals(2000L, s.connectMillis)
        val policy = s.policy()
        assertTrue(policy.isRelaxed)
        assertEquals(setOf("hn-stub.itwind.test", "other.test"), policy.allowedHosts)
    }

    @Test
    fun malformedValuesFailFast() {
        assertFailsWith<IllegalArgumentException> {
            UntrustedFetchSettings.fromModuleConfig(mapOf("untrusted_fetch" to mapOf("allowed_ports" to "80,http")))
        }
        assertFailsWith<IllegalArgumentException> {
            UntrustedFetchSettings.fromModuleConfig(mapOf("untrusted_fetch" to mapOf("allowed_networks" to "10.0.0.0/40")))
        }
    }

    @Test
    fun rejectionReasonsMapToMachineCodes() {
        assertEquals(MediaErrorCode.URL_SCHEME_FORBIDDEN, UntrustedHttpClient.rejectionCode(TargetRejection.SCHEME))
        assertEquals(MediaErrorCode.URL_PORT_FORBIDDEN, UntrustedHttpClient.rejectionCode(TargetRejection.PORT))
        assertEquals(MediaErrorCode.URL_TARGET_FORBIDDEN, UntrustedHttpClient.rejectionCode(TargetRejection.ADDRESS))
        assertEquals(MediaErrorCode.URL_RESOLVE_FAILED, UntrustedHttpClient.rejectionCode(TargetRejection.RESOLVE))
        assertEquals(OutboundAddressPolicy.DEFAULT_PORTS, UntrustedFetchSettings().allowedPorts)
    }
}
