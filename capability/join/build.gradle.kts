plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

// MIGRATION STEP 4: the Ktor clients (HttpEnrollment, HttpEventDirectory) moved to
// `:adapter:generic`. STEP 5: the join use-case + enroller moved to :domain
// feature/membership — this module is sourceless; the skeleton dies at step 6.
kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
    jvm()
    iosArm64()
    iosSimulatorArm64()
    sourceSets {
        commonMain.dependencies {
            api(project(":domain"))
            api(libs.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
        }
    }
}
