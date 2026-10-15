package controller.admin.file

import controller.admin.file.dto.FileInfoPageItemVO
import controller.admin.file.dto.FileInfoVO
import dto.PageResponse
import controller.admin.file.dto.FilePresignedUrlVO
import controller.admin.file.dto.UploadFileRequest
import logic.FileLogic
import logic.MediaLogic
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
    private val media: MediaLogic,
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
    ): PageResponse<FileInfoPageItemVO> {
        val page = fileLogic.pageFileInfo(pageNo, pageSize, name, configId)
        val counts = media.referenceCounts(page.list.mapNotNull { it.fileId })
        return PageResponse(
            page.list.map { f ->
                FileInfoPageItemVO(
                    id = f.id, fileId = f.fileId, ownerUid = f.ownerUid, businessType = f.businessType,
                    configId = f.configId, name = f.name, path = f.path, url = f.url, mimeType = f.mimeType,
                    size = f.size, sha256 = f.sha256, status = f.status, lifecycle = f.lifecycle,
                    lifecycleAt = f.lifecycleAt, purgedAt = f.purgedAt,
                    referenceCount = f.fileId?.let { counts[it] } ?: 0, createdAt = f.createdAt,
                )
            },
            page.total, page.page, page.size, page.totalPages,
        )
    }

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
