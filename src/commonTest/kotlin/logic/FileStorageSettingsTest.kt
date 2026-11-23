package logic

import model.FileConfig
import neton.core.http.BadRequestException
import kotlin.test.*

class FileStorageSettingsTest {
    private val s3 = FileStorageSettings(endpoint = "https://s3.example.com", region = "us-east-1", bucket = "assets", accessKeyEnv = "NETON_STORAGE_KEY", secretKeyEnv = "NETON_STORAGE_SECRET")

    @Test fun rejectsLegacyUnsupportedAndSecretPayloads() {
        assertNull(FileStorageSettings.decode(FileConfig(name = "old", storage = 1, config = "")))
        assertNull(FileStorageSettings.decode(FileConfig(name = "old", storage = 1, config = "{\"secretKey\":\"do-not-reflect\"}")))
        assertFailsWith<BadRequestException> { s3.validate(2) }
        assertEquals(s3, FileStorageSettings.decode(FileConfig(name = "valid", storage = 1, config = FileStorageSettings.encode(s3))))
    }

    @Test fun restrictsSecretsAndDestinations() {
        assertFailsWith<BadRequestException> { s3.copy(secretKeyEnv = "JWT_SECRET").validate(1) }
        assertFailsWith<BadRequestException> { s3.copy(endpoint = "https://user:password@s3.example.com").validate(1) }
        assertFailsWith<BadRequestException> { s3.copy(endpoint = "https://s3.example.com/path").validate(1) }
        assertFailsWith<BadRequestException> { s3.copy(baseUrl = "javascript:alert(1)").validate(1) }
        assertFailsWith<BadRequestException> { s3.source(1) { null } }
        val policy = FileStoragePolicy("/tmp/neton-assets", setOf(s3.endpoint)) { "credential" }
        assertEquals("credential", policy.resolve(1, s3).secretKey)
        assertFailsWith<BadRequestException> { policy.resolve(1, s3.copy(endpoint = "http://169.254.169.254")) }
        assertEquals("/tmp/neton-assets/photos", policy.resolve(0, FileStorageSettings(basePath = "photos")).basePath)
        listOf("../etc", "photos/../etc", "/etc", "a\\b").forEach { path ->
            assertFailsWith<BadRequestException> { policy.resolve(0, FileStorageSettings(basePath = path)) }
        }
        assertFailsWith<BadRequestException> { FileStoragePolicy(null, emptySet()) { null }.resolve(0, FileStorageSettings(basePath = "photos")) }
    }
}
