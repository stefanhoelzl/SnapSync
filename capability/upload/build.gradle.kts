plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    // MIGRATION STEP 5: the upload orchestration (UploadCycle, UploadArm, BackgroundUploadPump,
    // the cycle gate) moved to :domain's feature/upload zone. What remains is UploadPushReceiver —
    // the OS-callback receive seam, flow material for step 8.
    jvmToolchain(libs.versions.jdk.get().toInt())
    jvm()
    iosArm64()
    iosSimulatorArm64()
    sourceSets {
        commonMain.dependencies {
            api(project(":domain"))
            implementation(libs.coroutines.core)
            implementation(libs.kermit)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
        }
    }
}
