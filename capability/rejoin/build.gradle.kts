plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
    jvm()
    iosArm64()
    iosSimulatorArm64()
    sourceSets {
        commonMain.dependencies {
            api(libs.coroutines.core)
            // The join status seam it drives (kept light for presentation; re-exported so the app
            // can wire the same instance into both the join and the container).
            api(project(":capability:event-status"))
            // Implementation seams the join orchestrates — kept `implementation` so engine/gallery
            // types never leak transitively to the join's consumers (e.g. presentation).
            implementation(project(":domain:gallery"))
            implementation(project(":domain:engine"))
            implementation(project(":capability:config"))
            implementation(libs.ktor.client.core)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
        iosMain.dependencies {
            // Darwin (NSURLSession) engine for the on-device HTTPS fetch (default ATS).
            implementation(libs.ktor.client.darwin)
        }
    }
}
