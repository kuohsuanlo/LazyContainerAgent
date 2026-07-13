plugins {
    // 依 Minecraft 版本套用正確的 loom 變體
    id("dev.kikugie.loom-back-compat")
}

// 不要設定 group = ...!
version = "${property("mod.version")}+${sc.current.version}"
base.archivesName = property("mod.id") as String

// 1.21.x 全系列皆為 Java 21
val requiredJava: JavaVersion = JavaVersion.VERSION_21

// 是否為 ValueInput/ValueOutput 新 NBT API(1.21.6+)
val hasValueIo: Boolean = sc.current.parsed >= "1.21.6"

dependencies {
    minecraft("com.mojang:minecraft:${sc.current.version}")
    // 沿用原專案的 Mojang 官方映射(與 Paper NMS 命名一致)
    loomx.applyMojangMappings()

    modImplementation("net.fabricmc:fabric-loader:${property("deps.fabric_loader")}")
    // 純 Mixin 實作,不需要 Fabric API
}

loom {
    fabricModJsonPath = rootProject.file("src/main/resources/fabric.mod.json")

    decompilerOptions.named("vineflower") {
        options.put("mark-corresponding-synthetics", "1")
    }

    runConfigs.all {
        preferGradleTask = true
        generateRunConfig = true
        runDirectory = rootProject.file("run")
        jvmArguments.add("-Dmixin.debug.export=true")
    }
}

java {
    withSourcesJar()
    targetCompatibility = requiredJava
    sourceCompatibility = requiredJava

    toolchain {
        vendor = JvmVendorSpec.ADOPTIUM
        languageVersion = JavaLanguageVersion.of(requiredJava.majorVersion)
    }
}

tasks {
    processResources {
        fun MutableMap<String, String>.register(key: String, property: String) {
            val value: String = sc.properties[property]
            inputs.property(key, value)
            set(key, value)
        }

        val props = buildMap {
            register("id", "mod.id")
            register("name", "mod.name")
            register("version", "mod.version")
            register("minecraft", "mod.mc_compat")
        }

        filesMatching("fabric.mod.json") { expand(props) }

        // TagValueInputAccessor 僅 1.21.6+ 需要(ValueInput API)
        val accessorMixin = if (hasValueIo) ",\n    \"TagValueInputAccessor\"" else ""
        inputs.property("accessorMixin", accessorMixin)
        val mixinJava = "JAVA_${requiredJava.majorVersion}"
        filesMatching("*.mixins.json") {
            expand(mapOf("java" to mixinJava, "accessorMixin" to accessorMixin))
        }
    }

    register<Copy>("buildAndCollect") {
        group = "build"
        description = "建置各版本 jar 並收集到 build/libs/{mod version}/"

        inputs.property("version", project.property("mod.version"))
        from(loomx.modJar.flatMap { it.archiveFile }, loomx.modSourcesJar.flatMap { it.archiveFile })
        into(rootProject.layout.buildDirectory.file("libs/${project.property("mod.version")}"))
    }
}
