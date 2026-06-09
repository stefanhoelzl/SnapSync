plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
    jvm()
    sourceSets {
        commonMain.dependencies {
            api(project(":domain:sync"))
            api(libs.orbit.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.orbit.test)
            implementation(libs.coroutines.test)
        }
    }
}
