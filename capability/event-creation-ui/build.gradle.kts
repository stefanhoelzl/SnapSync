plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

// MIGRATION STEP 4: the Ktor client (HttpEventCreation) moved to `:adapter:generic`. What remains
// is the platform-free create-event use-case + creation status seam.
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
