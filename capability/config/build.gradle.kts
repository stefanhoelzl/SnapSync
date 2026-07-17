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
            implementation(libs.kotlinx.serialization.json)
            // Capability `photo-selection-policy`: "now" + local→UTC conversion for the capture-date cutoff.
            implementation(libs.kotlinx.datetime)
            // The three-state Keychain read (`KeychainRead`) that `ConfigRead` is derived from —
            // `api`, because it appears in `configReadFrom`'s public signature. The mapping is pure and
            // lives in commonMain so "unreadable is not absent" is tested on JVM and the simulator.
            api(project(":domain:keychain"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
        }
        iosMain.dependencies {
            // The Keychain store logs a legacy-item decode failure (capability `photo-selection-policy`):
            // a cutoff-less item reads as no config, and that must be diagnosable, not mysterious.
            implementation(libs.kermit)
        }
    }
}
