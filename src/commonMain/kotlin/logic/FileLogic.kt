package logic

import dto.PageResponse
import model.FileInfo
import model.FileConfig
import table.FileInfoTable
import table.FileConfigTable
import neton.database.dsl.*
import neton.database.api.DbContext

import neton.logging.Logger

@neton.core.annotations.Logic(logger = "logic.file")
class FileLogic(
    private val log: Logger,
    private val db: DbContext,
) {

    // --- FileInfo operations ---

    suspend fun createFileInfo(fileInfo: FileInfo): Long {
        val inserted = FileInfoTable.insert(fileInfo)
        log.info("Recorded file upload metadata with id: ${inserted.id}, name: ${fileInfo.name}")
        return inserted.id
    }

    suspend fun getFileInfo(id: Long): FileInfo? {
        return FileInfoTable.get(id)
    }

    suspend fun deleteFileInfo(id: Long) {
        FileInfoTable.destroy(id)
        log.info("Deleted file info with id: $id")
    }

    suspend fun pageFileInfo(
        page: Int,
        size: Int,
        name: String? = null,
        configId: Long? = null
    ): PageResponse<FileInfo> {
        val result = FileInfoTable.query {
            where {
                and(
                    whenNotBlank(name) { FileInfo::name like "%$it%" },
                    whenPresent(configId) { FileInfo::configId eq it }
                )
            }
            orderBy(FileInfo::id.desc())
        }.page(page, size)
        return PageResponse(result.items, result.total, page, size,
            if (size > 0) ((result.total + size - 1) / size).toInt() else 0)
    }

    data class PresignedUrlResult(
        val configId: Long,
        val uploadUrl: String,
        val url: String,
        val path: String
    )

    suspend fun getPresignedUrl(name: String, directory: String? = null): PresignedUrlResult {
        val masterConfig = FileConfigTable.oneWhere {
            FileConfig::master eq 1
        }

        if (masterConfig == null) {
            log.warn("No master file config found, cannot generate presigned URL")
            return PresignedUrlResult(0, "", "", "")
        }

        val dir = if (!directory.isNullOrBlank()) directory.trimEnd('/') + "/" else ""
        val filePath = "$dir$name"
        val fileUrl = "/infra/file/${masterConfig.id}/get/$filePath"

        log.info("Generating presigned URL for path: $filePath, storage type: ${masterConfig.storage}")
        return PresignedUrlResult(
            configId = masterConfig.id,
            uploadUrl = fileUrl,
            url = fileUrl,
            path = filePath
        )
    }

    // --- FileConfig operations ---

    suspend fun createFileConfig(fileConfig: FileConfig): Long {
        val inserted = FileConfigTable.insert(fileConfig)
        log.info("Created file config with id: ${inserted.id}, name: ${fileConfig.name}")
        return inserted.id
    }

    suspend fun updateFileConfig(fileConfig: FileConfig) {
        FileConfigTable.update(fileConfig)
        log.info("Updated file config with id: ${fileConfig.id}")
    }

    suspend fun deleteFileConfig(id: Long) {
        FileConfigTable.destroy(id)
        log.info("Deleted file config with id: $id")
    }

    suspend fun getFileConfig(id: Long): FileConfig? {
        return FileConfigTable.get(id)
    }

    suspend fun pageFileConfig(
        page: Int,
        size: Int,
        name: String? = null,
        storage: Int? = null
    ): PageResponse<FileConfig> {
        val result = FileConfigTable.query {
            where {
                and(
                    whenNotBlank(name) { FileConfig::name like "%$it%" },
                    whenPresent(storage) { FileConfig::storage eq it }
                )
            }
            orderBy(FileConfig::id.desc())
        }.page(page, size)
        return PageResponse(result.items, result.total, page, size,
            if (size > 0) ((result.total + size - 1) / size).toInt() else 0)
    }

    suspend fun updateMaster(id: Long) {
        // Reset all configs to non-master + set the specified config as master in a single transaction
        db.transaction {
            FileConfigTable.query {}.list().forEach { config ->
                if (config.master != 0) {
                    FileConfigTable.update(config.copy(master = 0))
                }
            }
            val config = FileConfigTable.get(id) ?: return@transaction
            FileConfigTable.update(config.copy(master = 1))
        }
        log.info("Set file config id: $id as master")
    }

    suspend fun testFileConfig(id: Long): Boolean {
        val config = FileConfigTable.get(id)
        if (config == null) {
            log.warn("File config not found for testing: id=$id")
            return false
        }

        return try {
            // Basic validation: check config has required fields
            if (config.name.isBlank()) {
                log.warn("File config name is blank: id=$id")
                return false
            }
            log.info("File config test passed for id=$id, name=${config.name}")
            true
        } catch (e: Exception) {
            log.error("File config test failed for id=$id: ${e.message}")
            false
        }
    }
}
