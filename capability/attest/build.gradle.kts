plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

// MIGRATION STEP 4: the Ktor client (HttpAttestClient) moved to `:adapter:generic`; the iOS
// adapters (IosAttestKey, KeychainAttestStore) to `:adapter:ios:ext-safe`. What remains is the
// platform-free DeviceAttestation policy + the in-memory store.
kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
    jvm()
    iosArm64()
    iosSimulatorArm64()
    sourceSets {
        commonMain.dependencies {
            api(project(":domain"))
            api(libs.coroutines.core)
            implementation(libs.kermit)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
        }
    }
}
