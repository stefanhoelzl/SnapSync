plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    // The platform-agnostic upload orchestration (UploadCycle + the UploadJobPlatform seam +
    // DiscoveryStore + UploadConfig), relocated out of :app:ios:photokit-extension so its jvm() target
    // runs the orchestration tests on JVM (testing rule 1) and the desktop harness can reach it. Depends
    // only on :domain:engine and :domain:gallery (the shared assetIdFromUploadKey parser reconstruct
    // uses) — no Compose/UI, no download-store/rejoin/ktor edges (those stay in the extension's
    // composition root). The iOS adapters (IosUploadJobPlatform, IosDiscoveryStore) stay in the
    // extension module, which composes this into its static framework.
    jvmToolchain(libs.versions.jdk.get().toInt())
    jvm()
    iosArm64()
    iosSimulatorArm64()
    sourceSets {
        commonMain.dependencies {
            api(project(":capability:push")) // the PushReceiver seam UploadPushReceiver implements
            implementation(project(":domain:logging"))
            implementation(project(":domain:engine"))
            implementation(project(":domain:gallery"))
            implementation(libs.coroutines.core)
            implementation(libs.kermit)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
        }
    }
}
