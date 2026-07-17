plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
    jvm()
    iosArm64()
    iosSimulatorArm64()
    sourceSets {
        commonMain.dependencies {
            api(project(":domain"))
            api(libs.coroutines.core)
            // Implementation seams the reconciliation orchestrates — kept `implementation` so engine
            // types never leak transitively to consumers that only want the leave use-case / file seam.
            implementation(project(":domain:engine"))
            // The shared upload-key inverse (`assetIdFromUploadKey`) the reconciler seeds ledger rows
            // with — one implementation, next to `uploadKey`, so the seed key parses identically to the
            // producer's (capability `gallery-status`).
            implementation(project(":domain:gallery"))
            implementation(libs.kermit)
            implementation(libs.ktor.client.core)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
        iosMain.dependencies {
            // Darwin (NSURLSession) engine for the on-device HTTPS fetch (default ATS).
            implementation(libs.ktor.client.darwin)
        }
    }
}
