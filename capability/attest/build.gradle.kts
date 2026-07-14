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
            api(libs.coroutines.core)
            // The device id the token is minted for (and the partition it authorizes writes to).
            implementation(project(":capability:device-id"))
            // The HTTP client is injected (Darwin on iOS, MockEngine in tests) — core + JSON only,
            // mirroring :capability:join.
            implementation(libs.ktor.client.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kermit)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
        // The Keychain-backed token store and the DCAppAttestService adapter. Capability
        // `architecture-guards`: `SecItem*` may appear ONLY in :domain:keychain, so this module borrows
        // that seam rather than carrying its own copy.
        iosMain.dependencies {
            api(project(":domain:keychain"))
        }
    }
}
