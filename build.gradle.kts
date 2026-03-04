import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.2.20"
    id("org.jetbrains.compose") version "1.9.1"
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.20"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.20"
    id("com.google.devtools.ksp") version "2.2.20-2.0.4"
}

group = "com.pokkerolli.codeagent"
version = "1.0.0"

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    implementation("io.insert-koin:koin-core:4.1.1")
    implementation("io.insert-koin:koin-compose:4.1.1")
    implementation("io.insert-koin:koin-compose-viewmodel:4.1.1")

    implementation("androidx.room:room-runtime:2.8.3")
    implementation("androidx.sqlite:sqlite-bundled:2.5.2")
    ksp("androidx.room:room-compiler:2.8.3")

    implementation("io.ktor:ktor-client-core:3.3.1")
    implementation("io.ktor:ktor-client-okhttp:3.3.1")
    implementation("io.ktor:ktor-client-content-negotiation:3.3.1")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.3.1")
    implementation("io.ktor:ktor-client-logging:3.3.1")

    implementation("org.slf4j:slf4j-api:2.0.9")

    testImplementation(kotlin("test"))
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
    arg("room.generateKotlin", "true")
}

compose.desktop {
    application {
        mainClass = "com.pokkerolli.codeagent.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "CodeAgent"
            packageVersion = "1.0.0"
        }
    }
}
