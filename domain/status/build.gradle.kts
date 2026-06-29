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
            // not the ledger. :capability:rejoin holds the EventFilesSource (its engine dep is
            // `implementation`, so no ledger type reaches status transitively).
            implementation(project(":domain:permission"))
            implementation(project(":domain:gallery"))
            implementation(project(":capability:rejoin"))
            api(libs.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
        }
    }
}
