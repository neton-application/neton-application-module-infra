pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

rootProject.name = "module-infra"

// 框架
includeBuild("../neton")
includeBuild("../geolite4k")
includeBuild("../neton-application-contract")

// 依赖的 canonical 模块（源码子项目，独立编译时 dependency substitution 生效）
includeBuild("../neton-application-module-system")
