package logic

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import model.FileConfig
import neton.core.http.BadRequestException
import neton.core.http.NotFoundException
import neton.database.api.DbContext
import neton.database.dsl.*
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
        try {
            val completed = withTimeoutOrNull(10_000) {
                var primary: Throwable? = null
                try {
                    operator.write(path, bytes)
                    check(operator.read(path).contentEquals(bytes))
                } catch (e: Throwable) {
                    primary = e
                    throw e
                } finally {
                    try {
                        withContext(NonCancellable) { withTimeout(5_000) { operator.delete(path) } }
                    } catch (cleanup: Throwable) {
                        if (primary == null) throw cleanup else primary.addSuppressed(cleanup)
                    }
                }
                true
            }
            if (completed != true) throw BadRequestException(InfraErrorCodes.FILE_STORAGE_TEST_FAILED)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            throw BadRequestException(InfraErrorCodes.FILE_STORAGE_TEST_FAILED)
        }
    }
}
