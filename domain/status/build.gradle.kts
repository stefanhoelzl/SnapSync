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
            // Implementation scope on purpose: status consumes permission and the gallery total, but
            // neither may leak to status's own consumers. Status does not depend on :domain:engine —
            // it projects counts through injected read seams, never a ledger type.
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
