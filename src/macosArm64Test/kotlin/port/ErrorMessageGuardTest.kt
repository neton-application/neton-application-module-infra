package port

import contract.testing.ErrorMessageGuard
import kotlin.test.Test

/** 业务错误 message 必须是 [InfraErrorCodes] 里的机器码（规范 infra-errors.md）。 */
class ErrorMessageGuardTest {
    @Test
    fun businessErrorMessagesAreMachineCodes() {
        ErrorMessageGuard.assertModuleClean(allowlist = emptySet())
    }
}

class InfraErrorCodesTest {
    /** MediaErrorCode 的名字都要在目录里，前端才能翻译调用方透传的结果码。 */
    @Test
    fun mediaErrorCodesAreCatalogued() {
        val catalogued = setOf(
            InfraErrorCodes.FILE_NOT_FOUND, InfraErrorCodes.FILE_DELETED, InfraErrorCodes.UNKNOWN_BUSINESS_TYPE,
            InfraErrorCodes.USAGE_NOT_ALLOWED, InfraErrorCodes.NOT_FILE_OWNER, InfraErrorCodes.NOT_REUSABLE,
            InfraErrorCodes.ACCESS_DENIED, InfraErrorCodes.INVALID_ARGUMENT, InfraErrorCodes.INVALID_BUSINESS_TYPE,
            InfraErrorCodes.INVALID_URL, InfraErrorCodes.UNSUPPORTED_SCHEME, InfraErrorCodes.FORBIDDEN_HOST,
            InfraErrorCodes.FETCH_FAILED, InfraErrorCodes.FILE_TOO_LARGE, InfraErrorCodes.INVALID_MIME_TYPE,
            InfraErrorCodes.EMPTY_FILE, InfraErrorCodes.NOT_AVAILABLE,
        )
        kotlin.test.assertEquals(MediaErrorCode.entries.map { it.name }.toSet(), catalogued)
    }
}
