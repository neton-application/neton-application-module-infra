plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// Kotlin 版本下限 2.4.20：低于它（包括 2.4.20 的 Beta / RC）直接拒绝编译。
// Neton 各仓走同一条 Kotlin 线 —— 混用时 K/N klib 与 KSP 产物会以很难看懂的方式失败。
run {
    val required = KotlinVersion(2, 4, 20)
    val actual = org.jetbrains.kotlin.gradle.plugin.getKotlinPluginVersion(logger)
    val parts = actual.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 } + listOf(0, 0, 0)
    val parsed = KotlinVersion(parts[0], parts[1], parts[2])
    require(parsed > required || (parsed == required && '-' !in actual)) {
        "Kotlin $actual is below the required minimum $required. Upgrade the Kotlin Gradle plugin."
    }
}

group = "com.netonstream.app"
version = "1.0.0"

repositories {
    mavenCentral()
}

kotlin {
    listOf(macosArm64(), linuxX64(), linuxArm64(), mingwX64()).forEach { target ->
        // 锚定到**本模块自身目录**：rootProject 在复合构建里会变成宿主工程
        // （privchat-application），那时 ../neton 会指向不存在的路径，
        // 链接期报 `library 'env' not found`。
        val coreInterop = project.file("../neton/neton-core/build/nativeInterop/${target.name}").absolutePath
        target.binaries.forEach { binary ->
            binary.linkerOpts.add("-L$coreInterop")
            binary.linkerOpts.add("-lenv")
        }
    }

    sourceSets {
        commonMain {
            dependencies {
                implementation("com.netonstream.app:module-system")
                implementation("com.netonstream:neton-core")
                implementation("com.netonstream:neton-routing")
                implementation("com.netonstream:neton-security")
                implementation("com.netonstream:neton-http")
                implementation("com.netonstream:neton-database")
                implementation("com.netonstream:neton-logging")
                implementation("com.netonstream:neton-validation")
                implementation("com.netonstream:neton-redis")
                implementation("com.netonstream:neton-cache")
                implementation("com.netonstream:neton-storage")
                implementation("com.netonstream:neton-jobs")
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.cryptography.core)
                implementation(libs.cryptography.provider.optimal)
            }
        }
        commonTest {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}

dependencies {
    add("kspMacosArm64", "com.netonstream:neton-ksp")
    add("kspLinuxX64", "com.netonstream:neton-ksp")
    add("kspLinuxArm64", "com.netonstream:neton-ksp")
    add("kspMingwX64", "com.netonstream:neton-ksp")
}

ksp {
    arg("neton.moduleId", "infra")
}

// Ensure Kotlin compilation sees KSP-generated commonMain sources.
// Required because facade tables delegate to generated XxxTableImpl —
// K2 metadata compilation needs them at the commonMain level.
afterEvaluate {
    val kspOut = file("build/generated/ksp/macosArm64/macosArm64Main/kotlin")
    kotlin.sourceSets.named("commonMain") {
        kotlin.srcDir(kspOut)
    }
    listOf("macosArm64Main", "linuxX64Main", "linuxArm64Main", "mingwX64Main").forEach { name ->
        kotlin.sourceSets.findByName(name)?.let { ss ->
            val filtered = ss.kotlin.srcDirs.filter { !it.path.contains("generated/ksp") }
            if (filtered.size < ss.kotlin.srcDirs.size) ss.kotlin.setSrcDirs(filtered)
        }
    }
}

tasks.matching { it.name == "compileCommonMainKotlinMetadata" }.configureEach {
    dependsOn("kspKotlinMacosArm64")
}
tasks.matching { it.name.matches(Regex("compileKotlin(MacosArm64|LinuxX64|LinuxArm64|MingwX64)")) }.configureEach {
    dependsOn("kspKotlinMacosArm64")
}
tasks.matching { it.name.matches(Regex("kspKotlin(LinuxX64|LinuxArm64|MingwX64)")) }.configureEach {
    dependsOn("kspKotlinMacosArm64")
}

// ============================================================
// Outbox store contract against a real PostgreSQL. Kept out of the
// regular unit test task; requires EVENT_DB_URI (role with CREATEDB).
//
//   ./gradlew :module-infra:macosArm64Test
//   EVENT_DB_URI=... ./gradlew :module-infra:postgresContractTest
// ============================================================
// outbox store + 媒体生命周期（infra-media §6）两组真实数据库契约，共用 EVENT_DB_URI。
val postgresContractClasses = listOf("event.PostgresDomainEventStoreDbTest", "logic.MediaLogicDbTest")

tasks.named<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest>("macosArm64Test") {
    postgresContractClasses.forEach { filter.excludeTestsMatching(it) }
}

// Runs the same test binary with kotlin-test's own filter.
tasks.register<Exec>("postgresContractTest") {
    group = "verification"
    description = "Runs the outbox store and media lifecycle contracts against a real PostgreSQL (EVENT_DB_URI required)."
    dependsOn("linkDebugTestMacosArm64")
    val binary = layout.buildDirectory.file("bin/macosArm64/debugTest/test.kexe")
    executable(binary.get().asFile.absolutePath)
    args("--ktest_filter=" + postgresContractClasses.joinToString(":") { "$it.*" }, "--ktest_logger=TEAMCITY")
    doFirst {
        check(!System.getenv("EVENT_DB_URI").isNullOrBlank()) {
            "postgresContractTest requires EVENT_DB_URI (a PostgreSQL URI whose role has CREATEDB). " +
                "This task is the RETRYABLE release gate and does not run without a real database."
        }
    }
}

// ============================================================
// Embed SQL migration resources into binary (no runtime file IO).
// ============================================================
extra["neton.migration.moduleId"] = "infra"
extra["neton.migration.dialects"] = listOf("postgresql")
extra["neton.migration.sqlSourceDir"] = file("sql")
apply(from = "../neton/scripts/embed-migration-resources.gradle.kts")
