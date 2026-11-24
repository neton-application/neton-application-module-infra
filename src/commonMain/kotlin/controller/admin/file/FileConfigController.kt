package controller.admin.file

import port.InfraErrorCodes
import controller.admin.file.dto.CreateFileConfigRequest
import controller.admin.file.dto.FileConfigVO
import controller.admin.file.dto.UpdateFileConfigRequest
import logic.FileLogic
import model.FileConfig
import neton.core.annotations.Controller
import neton.core.annotations.Get
import neton.core.annotations.Post
import neton.core.annotations.Put
import neton.core.annotations.Permission
import neton.core.annotations.Query
import neton.core.annotations.PathVariable
import neton.core.annotations.Body
import neton.core.annotations.RateLimit
import neton.core.annotations.RateLimitScope
import neton.core.http.NotFoundException
import neton.logging.Logger

@Controller("/infra/file-config")
class FileConfigController(
    private val log: Logger,
    private val fileLogic: FileLogic,
) {

    @Post("/create")
    @Permission("infra:file-config:create")
    @RateLimit(windowSeconds = 60, maxRequests = 20, scope = RateLimitScope.USER, message = InfraErrorCodes.FILE_STORAGE_RATE_LIMITED)
    suspend fun create(@Body request: CreateFileConfigRequest): Long {
        val fileConfig = FileConfig(
            name = request.name,
            storage = request.storage,
            config = logic.FileStorageSettings.encode(request.config),
            master = request.master,
            remark = request.remark
        )
        return fileLogic.createFileConfig(fileConfig)
    }

    @Put("/update")
    @Permission("infra:file-config:update")
    @RateLimit(windowSeconds = 60, maxRequests = 20, scope = RateLimitScope.USER, message = InfraErrorCodes.FILE_STORAGE_RATE_LIMITED)
    suspend fun update(@Body request: UpdateFileConfigRequest) {
        val existing = fileLogic.getFileConfig(request.id) ?: throw NotFoundException(InfraErrorCodes.FILE_CONFIG_NOT_FOUND)
        val fileConfig = existing.copy(
            name = request.name,
            storage = request.storage,
            config = logic.FileStorageSettings.encode(request.config),
            master = request.master,
            remark = request.remark
        )
        fileLogic.updateFileConfig(fileConfig)
    }

    @Put("/update-status/{id}")
    @Permission("infra:file-config:update")
    @RateLimit(windowSeconds = 60, maxRequests = 20, scope = RateLimitScope.USER, message = InfraErrorCodes.FILE_STORAGE_RATE_LIMITED)
    suspend fun updateStatus(@PathVariable id: Long, @Query status: Int) {
        fileLogic.updateFileConfigStatus(id, status)
    }

    @Get("/get/{id}")
    @Permission("infra:file-config:query")
    suspend fun get(@PathVariable id: Long): FileConfigVO {
        return fileLogic.getFileConfig(id)?.toVO() ?: throw NotFoundException(InfraErrorCodes.FILE_CONFIG_NOT_FOUND)
    }

    @Get("/page")
    @Permission("infra:file-config:query")
    suspend fun page(
        @Query pageNo: Int = 1,
        @Query pageSize: Int = 10,
        @Query name: String? = null,
        @Query storage: Int? = null,
        @Query status: Int? = null,
    ): dto.PageResponse<FileConfigVO> {
        val result = fileLogic.pageFileConfig(pageNo, pageSize, name, storage, status)
        return dto.PageResponse(result.list.map { it.toVO() }, result.total, result.page, result.size, result.totalPages)
    }

    @Put("/update-master/{id}")
    @Permission("infra:file-config:update")
    @RateLimit(windowSeconds = 60, maxRequests = 10, scope = RateLimitScope.USER, message = InfraErrorCodes.FILE_STORAGE_RATE_LIMITED)
    suspend fun updateMaster(@PathVariable id: Long) {
        fileLogic.updateMaster(id)
    }

    @Post("/test/{id}")
    @Permission("infra:file-config:update")
    @RateLimit(windowSeconds = 60, maxRequests = 10, scope = RateLimitScope.USER, message = InfraErrorCodes.FILE_STORAGE_RATE_LIMITED)
    suspend fun test(@PathVariable id: Long): Boolean {
        return fileLogic.testFileConfig(id)
    }

    private fun FileConfig.toVO(): FileConfigVO {
        val safe = logic.FileStorageSettings.decode(this)
        return FileConfigVO(id, name, storage, master, remark, safe, safe != null, testPassed, status)
    }
}
