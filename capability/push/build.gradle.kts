plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

// MIGRATION STEP 4: the Ktor client (KtorPushHttpClient) moved to `:adapter:generic`. What remains
// is the platform-free registration collector + event notifier over the PushHttpClient port.
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
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
            // PushRegistrationTest drives the real KtorPushHttpClient (now in :adapter:generic)
            // over a MockEngine.
            implementation(project(":adapter:generic"))
            implementation(libs.ktor.client.mock)
        }
    }
}
