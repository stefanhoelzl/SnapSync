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
            api(project(":domain"))
            // MIGRATION STEP 5: the ledger→SyncStatus projections moved to :domain feature/status.
            // What remains is DownloadStatusSource (the download arm's read-model — it moves with the
            // download feature at step 6) plus the two stay-behind tests that drive the moved
            // projections through :domain:gallery's in-memory fakes (which :domain cannot reach).
            implementation(project(":domain:permission"))
            implementation(project(":domain:gallery"))
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
