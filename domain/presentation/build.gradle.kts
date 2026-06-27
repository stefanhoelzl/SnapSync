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
            api(project(":domain:permission"))
            api(project(":domain:status"))
            // The container consumes the config seam + decoder (onOpenUrl), and ConfigSource/
            // ConfigStore appear in its constructor — so they surface in this module's API.
            api(project(":capability:config"))
            // The join status seam folded into the reduction; surfaces in the container's API.
            api(project(":capability:event-status"))
            api(libs.orbit.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.orbit.test)
            implementation(libs.coroutines.test)
        }
    }
}
