package logic

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import model.FileConfig
import neton.core.http.BadRequestException
import neton.storage.SourceConfig
import port.InfraErrorCodes

/** Persist references, never credential values. Unknown/legacy payloads are not reflected back. */
@Serializable
data class FileStorageSettings(
    val basePath: String = "",
    val endpoint: String = "",
    val region: String = "",
    val bucket: String = "",
    val accessKeyEnv: String = "",
    val secretKeyEnv: String = "",
    val pathStyle: Boolean = false,
    val baseUrl: String = "",
) {
    fun validate(storage: Int) {
        fun invalid(): Nothing = throw BadRequestException(InfraErrorCodes.FILE_STORAGE_INVALID)
        if (listOf(basePath, endpoint, region, bucket, accessKeyEnv, secretKeyEnv, baseUrl).any { it.length > 1024 || it != it.trim() || it.any { c -> c.isISOControl() } }) invalid()
        if (baseUrl.isNotEmpty() && !isOrigin(baseUrl)) invalid()
        when (storage) {
            0 -> if (basePath.isBlank() || basePath.split('/').any { it == "." || it == ".." } || '\\' in basePath || endpoint.isNotEmpty() || accessKeyEnv.isNotEmpty() || secretKeyEnv.isNotEmpty()) invalid()
            1 -> if (!isOrigin(endpoint) || region.isBlank() || !Regex("[a-zA-Z0-9][a-zA-Z0-9._-]{0,62}").matches(bucket) || !ENV.matches(accessKeyEnv) || !ENV.matches(secretKeyEnv) || basePath.isNotEmpty()) invalid()
            else -> invalid()
        }
    }

    fun source(storage: Int, env: (String) -> String?): SourceConfig {
        validate(storage)
        fun secret(key: String) = env(key)?.takeIf { it.isNotBlank() }
            ?: throw BadRequestException(InfraErrorCodes.FILE_STORAGE_SECRET_MISSING)
        return SourceConfig(
            type = if (storage == 0) "local" else "s3", basePath = basePath, endpoint = endpoint,
            region = region, bucket = bucket, pathStyle = pathStyle, baseUrl = baseUrl,
            accessKey = if (storage == 1) secret(accessKeyEnv) else "",
            secretKey = if (storage == 1) secret(secretKeyEnv) else "",
        )
    }

    companion object {
        private val ENV = Regex("NETON_STORAGE_[A-Z][A-Z0-9_]{0,100}")
        private val json = Json { ignoreUnknownKeys = false }
        // Origins only: no userinfo, query, fragment, path traversal or ambiguous URL syntax.
        fun isOrigin(value: String): Boolean = Regex("https?://[a-zA-Z0-9.-]+(?::[0-9]{1,5})?").matches(value)
        fun decode(record: FileConfig): FileStorageSettings? = try {
            json.decodeFromString<FileStorageSettings>(record.config).also { it.validate(record.storage) }
        } catch (_: IllegalArgumentException) { null }
        catch (_: neton.core.http.HttpException) { null }
        fun encode(settings: FileStorageSettings) = json.encodeToString(serializer(), settings)
    }
}
