plugins {
    kotlin("jvm") version "2.3.0"
    id("com.gradleup.shadow") version "9.6.1"
}

group = "luminaworld.luminahud"
version = "1.0.0-SNAPSHOT"
description = "Clean Room SVG-to-Font HUD Engine for Paper/Purpur/Folia"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.codemc.io/repository/maven-releases/")
    maven("https://repo.codemc.io/repository/maven-snapshots/")
    maven("https://repo.extendedclip.com/releases/")
}

dependencies {
    // Paper API 26.3
    compileOnly("io.papermc.paper:paper-api:26.3.build.+")

    // Kotlin
    implementation("org.jetbrains.kotlin:kotlin-stdlib")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")

    // PacketEvents (Optional API for Paper / Folia)
    compileOnly("com.github.retrooper:packetevents-spigot:2.14.0")

    // Apache Batik (SVG Rasterizer)
    implementation("org.apache.xmlgraphics:batik-transcoder:1.18")
    implementation("org.apache.xmlgraphics:batik-codec:1.18")

    // Javalin (Embedded Web Server)
    implementation("io.javalin:javalin:6.4.0")

    // PlaceholderAPI (Softdepend)
    compileOnly("me.clip:placeholderapi:2.11.6")

    // SLF4J API (provided by Paper/Server environment)
    compileOnly("org.slf4j:slf4j-api:2.0.16")

    // Testing
    testImplementation("io.papermc.paper:paper-api:26.3.build.+")
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(25)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

tasks {
    compileJava {
        options.encoding = "UTF-8"
        options.release.set(25)
    }

    compileKotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25)
            freeCompilerArgs.addAll(
                "-Xjsr305=strict",
                "-opt-in=kotlin.RequiresOptIn",
                "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi"
            )
        }
    }

    jar {
        enabled = false
    }

    shadowJar {
        archiveClassifier.set("")
        archiveFileName.set("LuminaHUD-${project.version}.jar")

        exclude("META-INF/*.SF")
        exclude("META-INF/*.DSA")
        exclude("META-INF/*.RSA")

        // Relocations to prevent classpath conflicts
        relocate("io.javalin", "luminaworld.luminahud.libs.javalin")
        relocate("org.eclipse.jetty", "luminaworld.luminahud.libs.jetty")
        relocate("org.apache.batik", "luminaworld.luminahud.libs.batik")
        relocate("org.apache.xmlgraphics", "luminaworld.luminahud.libs.xmlgraphics")
    }

    processResources {
        filteringCharset = "UTF-8"
        val properties = mapOf(
            "version" to project.version,
            "description" to project.description
        )
        inputs.properties(properties)
        filesMatching(listOf("paper-plugin.yml", "plugin.yml")) {
            expand(properties)
        }
    }

    test {
        useJUnitPlatform()
    }

    build {
        dependsOn(shadowJar)
    }
}
