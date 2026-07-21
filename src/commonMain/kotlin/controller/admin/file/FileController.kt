package controller.admin.file

import controller.admin.file.dto.FileInfoVO
import controller.admin.file.dto.FilePresignedUrlVO
import controller.admin.file.dto.UploadFileRequest
import logic.FileLogic
import model.FileInfo
import neton.core.annotations.Controller
import neton.core.annotations.Get
import neton.core.annotations.Post
import neton.core.annotations.Delete
import neton.core.annotations.Permission
import neton.core.annotations.Query
import neton.core.annotations.PathVariable
import neton.core.annotations.Body
import neton.logging.Logger

@Controller("/infra/file")
class FileController(
    private val log: Logger,
    private val fileLogic: FileLogic,
) {

    @Post("/upload")
    @Permission("infra:file:upload")
    suspend fun upload(@Body request: UploadFileRequest): Long {
        val fileInfo = FileInfo(
            configId = request.configId,
            name = request.name,
            path = request.path,
            url = request.url,
            mimeType = request.mimeType,
            size = request.size
        )
        return fileLogic.createFileInfo(fileInfo)
    }

    @Get("/get/{id}")
    @Permission("infra:file:query")
    suspend fun get(@PathVariable id: Long): FileInfo? {
        return fileLogic.getFileInfo(id)
    }

    @Delete("/delete/{id}")
    @Permission("infra:file:delete")
    suspend fun delete(@PathVariable id: Long) {
        fileLogic.deleteFileInfo(id)
    }

    @Delete("/delete-list")
    @Permission("infra:file:delete")
    suspend fun deleteList(@Query ids: String) {
        ids.split(",").mapNotNull { it.trim().toLongOrNull() }.forEach { fileLogic.deleteFileInfo(it) }
    }

    @Get("/page")
    @Permission("infra:file:query")
    suspend fun page(
        @Query pageNo: Int = 1,
        @Query pageSize: Int = 10,
        @Query name: String? = null,
        @Query configId: Long? = null
    ) = fileLogic.pageFileInfo(pageNo, pageSize, name, configId)

    @Get("/presigned-url")
    @Permission("infra:file:query")
    suspend fun presignedUrl(@Query name: String, @Query directory: String? = null): FilePresignedUrlVO {
        val result = fileLogic.getPresignedUrl(name, directory)
        return FilePresignedUrlVO(
            configId = result.configId,
            uploadUrl = result.uploadUrl,
            url = result.url,
            path = result.path
        )
    }
}
