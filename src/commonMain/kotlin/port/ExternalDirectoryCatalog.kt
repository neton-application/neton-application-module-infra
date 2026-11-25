package port

import neton.database.api.DbContext

internal const val EXTERNAL_DIRECTORY_MIME = "inode/directory"

/** Registers storage metadata using the caller's transaction. The caller verifies the directory in storage. */
suspend fun DbContext.registerExternalDirectory(name: String, path: String, businessType: String, createdAt: Long): Long {
    val parameters = mapOf("name" to name, "path" to path, "businessType" to businessType, "createdAt" to createdAt)
    val inserted = fetchAll(
        """
        INSERT INTO infra_files (name, path, business_type, mime_type, created_at)
        VALUES (:name, :path, :businessType, 'inode/directory', :createdAt)
        ON CONFLICT (path) WHERE mime_type = 'inode/directory' DO NOTHING
        RETURNING id
        """.trimIndent(),
        parameters,
    ).singleOrNull()
    return inserted?.long("id") ?: fetchAll(
        "SELECT id FROM infra_files WHERE path = :path AND mime_type = 'inode/directory' FOR KEY SHARE",
        mapOf("path" to path),
    ).single().long("id")
}
