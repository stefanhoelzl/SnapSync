// `:adapter:ios:ext-safe` (spec `module-architecture`): every iOS adapter the background-upload
// EXTENSION process links — placed by linkage, so the extension binary's contents are decided by
// this module boundary rather than by luck. The extension-safety text gate
// (`:test:architecture` ExtensionSafetyTest) forbids `platform.UIKit`/`platform.BackgroundTasks`
// anywhere under this module, because Kotlin/Native does not model `NS_EXTENSION_UNAVAILABLE`.
// This is also the Keychain containment module — the ONLY module that may touch `SecItem*`
// (capability `architecture-guards`; KeychainContainmentTest).

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    iosArm64()
    iosSimulatorArm64()
    sourceSets {
        commonMain.dependencies {
            api(project(":domain"))
            // The SQLDelight stores these drivers open (SqlDelightLedgerStore/SqlDelightDownloadStore)
            // and the Ktor core types darwinHttpClient() returns.
            api(project(":adapter:generic:app"))
            // (The interim :capability:album and :domain:gallery edges died at migration step 6:
            // the album seams now live in :domain ports/, albumMapSource in feature/album, and the
            // ResourceEnumerator composition in feature/upload — all reached via api(":domain").)
            implementation(libs.sqldelight.driver.native)
            implementation(libs.ktor.client.darwin)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kermit)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
