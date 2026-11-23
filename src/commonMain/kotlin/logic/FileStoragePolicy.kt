package logic

import neton.core.http.BadRequestException
import neton.storage.SourceConfig
import port.InfraErrorCodes

/** Deployment authority is separate from the administrator's editable configuration. */
class FileStoragePolicy(
    private val localRoot: String?,
    private val allowedEndpoints: Set<String>,
    private val env: (String) -> String?,
) {
    fun resolve(storage: Int, settings: FileStorageSettings): SourceConfig {
        settings.validate(storage)
        if (storage == 0) {
            val root = localRoot?.trimEnd('/')?.takeIf { it.startsWith('/') && it.isNotBlank() && it.split('/').none { p -> p == "." || p == ".." } }
                ?: throw BadRequestException(InfraErrorCodes.FILE_STORAGE_NOT_ALLOWED)
            if (settings.basePath.startsWith('/') || settings.basePath.contains(':')) throw BadRequestException(InfraErrorCodes.FILE_STORAGE_NOT_ALLOWED)
            return settings.source(storage, env).copy(basePath = "$root/${settings.basePath}")
        }
        if (settings.endpoint !in allowedEndpoints) throw BadRequestException(InfraErrorCodes.FILE_STORAGE_NOT_ALLOWED)
        return settings.source(storage, env)
    }
}
