package controller.admin.file

import controller.admin.file.dto.CreateFileConfigRequest
import controller.admin.file.dto.FileConfigVO
import controller.admin.file.dto.UpdateFileConfigRequest
import logic.FileLogic
import model.FileConfig
import neton.core.annotations.Controller
import neton.core.annotations.Get
import neton.core.annotations.Post
import neton.core.annotations.Put
import neton.core.annotations.Delete
import neton.core.annotations.Permission
import neton.core.annotations.Query
import neton.core.annotations.PathVariable
import neton.core.annotations.Body
import neton.core.http.NotFoundException
import neton.logging.Logger

@Controller("/infra/file-config")
class FileConfigController(
    private val log: Logger,
    private val fileLogic: FileLogic,
) {

    @Post("/create")
    @Permission("infra:file-config:create")
    suspend fun create(@Body request: CreateFileConfigRequest): Long {
        val fileConfig = FileConfig(
            name = request.name,
            storage = request.storage,
            config = "",
            master = request.master,
            remark = request.remark
        )
        return fileLogic.createFileConfig(fileConfig)
    }

    @Put("/update")
    @Permission("infra:file-config:update")
    suspend fun update(@Body request: UpdateFileConfigRequest) {
        val existing = fileLogic.getFileConfig(request.id) ?: throw NotFoundException("File config not found")
        val fileConfig = existing.copy(
            name = request.name,
            storage = request.storage,
            master = request.master,
            remark = request.remark
        )
        fileLogic.updateFileConfig(fileConfig)
    }

    @Delete("/delete/{id}")
    @Permission("infra:file-config:delete")
    suspend fun delete(@PathVariable id: Long) {
        fileLogic.deleteFileConfig(id)
    }

    @Delete("/delete-list")
    @Permission("infra:file-config:delete")
    suspend fun deleteList(@Query ids: String) {
        ids.split(",").mapNotNull { it.trim().toLongOrNull() }.forEach { fileLogic.deleteFileConfig(it) }
    }

    @Get("/get/{id}")
    @Permission("infra:file-config:query")
    suspend fun get(@PathVariable id: Long): FileConfig? {
        return fileLogic.getFileConfig(id)
    }

    @Get("/page")
    @Permission("infra:file-config:query")
    suspend fun page(
        @Query pageNo: Int = 1,
        @Query pageSize: Int = 10,
        @Query name: String? = null,
        @Query storage: Int? = null
    ) = fileLogic.pageFileConfig(pageNo, pageSize, name, storage)

    @Put("/update-master/{id}")
    @Permission("infra:file-config:update")
    suspend fun updateMaster(@PathVariable id: Long) {
        fileLogic.updateMaster(id)
    }

    @Get("/test/{id}")
    @Permission("infra:file-config:query")
    suspend fun test(@PathVariable id: Long): Boolean {
        return fileLogic.testFileConfig(id)
    }
}
