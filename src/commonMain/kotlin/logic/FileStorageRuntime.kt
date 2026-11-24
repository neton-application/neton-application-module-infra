package logic

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import model.FileConfig
import neton.core.http.BadRequestException
import neton.core.http.NotFoundException
import neton.database.api.DbContext
import neton.database.dsl.*
import neton.storage.StorageAccessDeniedException
import neton.storage.StorageOperator
import port.InfraErrorCodes
import table.FileConfigTable

/** One routing authority for uploads, reads, probes and garbage collection. */
class FileStorageRuntime(
    private val db: DbContext,
    private val fallback: StorageOperator,
    private val create: (FileConfig, FileStorageSettings) -> StorageOperator,
) {
    data class Target(val configId: Long?, val operator: StorageOperator)

    suspend fun <T> locked(shared: Boolean = false, block: suspend () -> T): T = db.transaction {
        execute(if (shared) "SELECT pg_advisory_xact_lock_shared(725401920)" else "SELECT pg_advisory_xact_lock(725401920)")
        block()
    }

    suspend fun selected(): Target {
        val record = FileConfigTable.oneWhere { FileConfig::master eq 1 }
        return if (record == null) Target(null, fallback) else Target(record.id, operator(record))
    }

    suspend fun forFile(configId: Long?): StorageOperator = if (configId == null) fallback else operator(
        FileConfigTable.get(configId) ?: throw NotFoundException(InfraErrorCodes.FILE_CONFIG_NOT_FOUND),
    )

    fun operator(record: FileConfig): StorageOperator = create(record, FileStorageSettings.decode(record)
        ?: throw BadRequestException(InfraErrorCodes.FILE_STORAGE_INVALID))

    /** Success means the complete write/read/delete cycle passed, not just a HEAD request. */
    suspend fun probe(record: FileConfig) {
        val operator = operator(record)
        val path = "_neton_probe/" + neton.security.random.SecureRandom.hex(20)
        val bytes = "neton-storage-probe".encodeToByteArray()
        var primary: Throwable? = null
        try {
            probeStep(ProbeStep.WRITE) { operator.write(path, bytes) }
            probeStep(ProbeStep.READ) {
                if (!operator.read(path).contentEquals(bytes)) {
                    throw BadRequestException(InfraErrorCodes.FILE_STORAGE_TEST_MISMATCH)
                }
            }
        } catch (failure: Throwable) {
            primary = failure
            throw failure
        } finally {
            try {
                withContext(NonCancellable) {
                    probeStep(ProbeStep.DELETE) { operator.delete(path) }
                }
            } catch (cleanup: Throwable) {
                if (primary == null) throw cleanup
                primary.addSuppressed(cleanup)
            }
        }
    }

    private suspend fun probeStep(step: ProbeStep, operation: suspend () -> Unit) {
        val completed = try {
            withTimeoutOrNull(5_000) {
                operation()
                true
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: BadRequestException) {
            throw failure
        } catch (_: StorageAccessDeniedException) {
            throw BadRequestException(step.denied)
        } catch (_: Exception) {
            throw BadRequestException(step.failed)
        }
        if (completed != true) throw BadRequestException(step.timedOut)
    }

    private enum class ProbeStep(val failed: String, val denied: String, val timedOut: String) {
        WRITE(InfraErrorCodes.FILE_STORAGE_WRITE_FAILED, InfraErrorCodes.FILE_STORAGE_WRITE_DENIED, InfraErrorCodes.FILE_STORAGE_WRITE_TIMEOUT),
        READ(InfraErrorCodes.FILE_STORAGE_READ_FAILED, InfraErrorCodes.FILE_STORAGE_READ_DENIED, InfraErrorCodes.FILE_STORAGE_READ_TIMEOUT),
        DELETE(InfraErrorCodes.FILE_STORAGE_DELETE_FAILED, InfraErrorCodes.FILE_STORAGE_DELETE_DENIED, InfraErrorCodes.FILE_STORAGE_DELETE_TIMEOUT),
    }
}
