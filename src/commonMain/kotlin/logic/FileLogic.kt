package logic

import dto.PageResponse
import model.FileInfo
import model.FileConfig
import table.FileInfoTable
import table.FileConfigTable
import neton.database.dsl.*
import neton.database.api.DbContext

import neton.logging.Logger
import neton.core.http.BadRequestException
import neton.core.http.NotFoundException
import port.EXTERNAL_DIRECTORY_MIME
import port.InfraErrorCodes

class FileLogic(
    private val log: Logger,
    private val db: DbContext,
    private val storage: FileStorageRuntime,
) {

    // --- FileInfo operations ---

    suspend fun createFileInfo(fileInfo: FileInfo): Long {
        if (fileInfo.mimeType == EXTERNAL_DIRECTORY_MIME) throw BadRequestException(InfraErrorCodes.INVALID_ARGUMENT)
        val inserted = FileInfoTable.insert(fileInfo)
        log.info("Recorded file upload metadata with id: ${inserted.id}, name: ${fileInfo.name}")
        return inserted.id
    }

    suspend fun getFileInfo(id: Long): FileInfo? {
        return FileInfoTable.get(id)
    }

    suspend fun deleteFileInfo(id: Long) {
        db.transaction {
            // 行没了引用也一并解除，免得引用表里留下指向不存在文件的记录
            execute(
                "DELETE FROM infra_media_references WHERE file_id IN (SELECT file_id FROM infra_files WHERE id = :id AND file_id IS NOT NULL)",
                mapOf("id" to id),
            )
            FileInfoTable.destroy(id)
        }
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
        // Direct upload bypasses media validation/lifecycle. Do not manufacture a signature.
        throw BadRequestException(InfraErrorCodes.FILE_STORAGE_PRESIGN_UNSUPPORTED)
    }

    // --- FileConfig operations ---

    suspend fun createFileConfig(fileConfig: FileConfig): Long = storage.locked {
        validate(fileConfig)
        val inserted = FileConfigTable.insert(fileConfig.copy(master = 0, testPassed = null, status = 1))
        log.info("Created file config with id: ${inserted.id}, name: ${fileConfig.name}")
        inserted.id
    }

    suspend fun updateFileConfig(fileConfig: FileConfig) = storage.locked {
        val current = FileConfigTable.get(fileConfig.id) ?: throw NotFoundException(InfraErrorCodes.FILE_CONFIG_NOT_FOUND)
        validate(fileConfig)
        val changed = current.storage != fileConfig.storage || FileStorageSettings.decode(current) != FileStorageSettings.decode(fileConfig)
        if (changed && referenced(fileConfig.id)) throw BadRequestException(InfraErrorCodes.FILE_CONFIG_IN_USE)
        if (changed && current.master == 1) throw BadRequestException(InfraErrorCodes.FILE_CONFIG_MASTER_EDIT)
        FileConfigTable.update(fileConfig.copy(
            master = current.master,
            status = current.status,
            testPassed = if (changed) null else current.testPassed,
        ))
        log.info("Updated file config with id: ${fileConfig.id}")
    }

    suspend fun updateFileConfigStatus(id: Long, status: Int) = storage.locked {
        if (status !in 0..1) throw BadRequestException(InfraErrorCodes.INVALID_ARGUMENT)
        val current = FileConfigTable.get(id) ?: throw NotFoundException(InfraErrorCodes.FILE_CONFIG_NOT_FOUND)
        if (status == 0 && current.master == 1) {
            throw BadRequestException(InfraErrorCodes.FILE_CONFIG_MASTER_DISABLE)
        }
        if (current.status != status) FileConfigTable.update(current.copy(status = status))
    }

    suspend fun getFileConfig(id: Long): FileConfig? {
        return FileConfigTable.get(id)
    }

    suspend fun pageFileConfig(
        page: Int,
        size: Int,
        name: String? = null,
        storage: Int? = null,
        status: Int? = null,
    ): PageResponse<FileConfig> {
        val result = FileConfigTable.query {
            where {
                and(
                    whenNotBlank(name) { FileConfig::name like "%$it%" },
                    whenPresent(storage) { FileConfig::storage eq it },
                    whenPresent(status) { FileConfig::status eq it }
                )
            }
            orderBy(FileConfig::id.desc())
        }.page(page, size)
        return PageResponse(result.items, result.total, page, size,
            if (size > 0) ((result.total + size - 1) / size).toInt() else 0)
    }

    suspend fun updateMaster(id: Long) {
        runTest(id, activate = true)
    }

    suspend fun testFileConfig(id: Long): Boolean {
        runTest(id, activate = false)
        return true
    }

    private suspend fun runTest(id: Long, activate: Boolean) {
        val rejection = storage.locked {
            val config = FileConfigTable.get(id) ?: throw NotFoundException(InfraErrorCodes.FILE_CONFIG_NOT_FOUND)
            if (activate && config.status != 1) throw BadRequestException(InfraErrorCodes.FILE_CONFIG_DISABLED)
            val failure = try {
                storage.probe(config)
                null
            } catch (failure: BadRequestException) {
                failure
            }
            if (failure == null && activate) {
                clearMaster()
                FileConfigTable.update(config.copy(master = 1, testPassed = true))
                log.info("Set file config id: $id as master")
            } else {
                FileConfigTable.update(config.copy(testPassed = failure == null))
            }
            failure
        }
        // Commit the test outcome before returning a rejected probe to the caller.
        if (rejection != null) throw rejection
    }

    private suspend fun clearMaster() { db.execute("UPDATE infra_file_configs SET master = 0 WHERE master = 1") }
    private suspend fun referenced(id: Long): Boolean = db.fetchAll("SELECT 1 AS ok FROM infra_files WHERE config_id = :id LIMIT 1", mapOf("id" to id)).isNotEmpty()
    private fun validate(record: FileConfig) {
        if (record.name.isBlank() || record.name.length > 64 || (record.remark?.length ?: 0) > 255 || record.master !in 0..1) throw BadRequestException(InfraErrorCodes.INVALID_ARGUMENT)
        if (FileStorageSettings.decode(record) == null) throw BadRequestException(InfraErrorCodes.FILE_STORAGE_INVALID)
    }
}
