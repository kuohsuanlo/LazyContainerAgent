pluginManagement {
    repositories {
        mavenLocal()
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.fabricmc.net/")
        maven("https://maven.kikugie.dev/releases") { name = "KikuGie Releases" }
        maven("https://maven.kikugie.dev/snapshots") { name = "KikuGie Snapshots" }
    }
}

plugins {
    id("dev.kikugie.stonecutter") version "0.9.6"
    // 依 Minecraft 版本自動套用對應的 loom 變體
    id("dev.kikugie.loom-back-compat") version "0.4"
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

stonecutter {
    create(rootProject) {
        // 每個目標建出一個 jar,涵蓋範圍見 stonecutter.properties.toml 的 mod.mc_compat
        versions("1.21.1", "1.21.4", "1.21.5", "1.21.8", "1.21.11")
        vcsVersion = "1.21.11"
    }
}

rootProject.name = "LazyContainerFabric"
