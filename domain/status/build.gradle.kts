plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
    jvm()
    iosArm64()
    iosSimulatorArm64()
    sourceSets {
        commonMain.dependencies {
            // Implementation scope on purpose: status consumes permission, the gallery total, and the
            // event file-list seam (the completeness listing), but none may leak to status's own
            // consumers. Status no longer depends on :domain:engine — it derives from storage truth,
            // not the ledger. :capability:membership holds the EventFilesSource (its engine dep is
            // `implementation`, so no ledger type reaches status transitively).
            implementation(project(":domain:permission"))
            implementation(project(":domain:gallery"))
            implementation(project(":capability:membership"))
            api(libs.coroutines.core)
            // The own-device total logs its enumeration cost (capability `diagnostic-logging`): the walk
            // is one synchronous PhotoKit round-trip per in-scope asset, and that cost is the whole reason
            // the capture-date bound exists. Without this line the bound's effect is unobservable on device.
            implementation(libs.kermit)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
        }
    }
}
