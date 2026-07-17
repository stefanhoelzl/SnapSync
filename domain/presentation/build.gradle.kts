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
            // The container consumes the config seam + decoder (onOpenUrl), and ConfigSource/
            // ConfigStore appear in its constructor — so they surface in this module's API.
            // The create-event seams (CreationStatusSource/EventCreator) folded into the reduction and
            // the container's constructor — so they surface in this module's API.
            api(libs.orbit.core)
            // Capability `photo-selection-policy`: LocalDateTime appears in CutoffFormatter's signature (used
            // by the join screen in :domain:ui), so it is part of this module's API.
            api(libs.kotlinx.datetime)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.orbit.test)
            implementation(libs.coroutines.test)
        }
    }
}
