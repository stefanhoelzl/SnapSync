plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
    jvm()
    iosArm64()
    iosSimulatorArm64()
    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        // The Keychain adapter + the read/mint/migrate decision. Capability `architecture-guards`:
        // `SecItem*` may appear ONLY in :domain:keychain, so this module borrows it rather than
        // carrying its own copy. `api`, because the `Keychain` seam is a constructor parameter of
        // KeychainDeviceIdentity.
        iosMain.dependencies {
            api(project(":domain:keychain"))
        }
    }
}
