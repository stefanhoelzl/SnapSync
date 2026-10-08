// `:adapter:android` (`docs/architecture.md`): every Android adapter of the `:domain` ports — ONE module, not the
// iOS ext-safe / app-only / ui split. iOS splits because its upload extension is a second process with linkage limits
// of its own; on Android the workers, the push service and the activity all run in the app's one process, so there is
// no second binary for a linkage line to protect.
//
// Today it holds what the screen and its foreground life need, the storage adapters (files, databases, preferences,
// the Keystore-sealed secure store, the platform device id) and the platform log. Every other system's adapter arrives
// with the phase that needs it. Its contract bindings are device tests (`src/androidDeviceTest`): they need ART and the
// platform's SQLite and Keystore, so they run on the emulator (`androidPlatformTest`).
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    // The `android` target (`docs/architecture.md`, "Zones inside the core") — this module's only one.
    id("snapsync.android")
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
    sourceSets {
        androidMain.dependencies {
            api(project(":domain:model"))
            api(project(":domain:ports"))
            implementation(project(":domain:presentation"))
            implementation(project(":ui:screens"))
            implementation(project(":ui:components"))
            implementation(libs.coroutines.core)
            implementation(libs.kermit)
            // The date formatting adapter's wall-clock values.
            implementation(libs.kotlinx.datetime)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            // The process's foreground life (`ProcessLifecycleOwner`).
            implementation(libs.androidx.lifecycle.process)
            // The activity-result registry the photo-permission dialog is launched through.
            implementation(libs.androidx.activity.compose)
            // The wakes, the background-time holds and the uploads run as WorkManager work.
            implementation(libs.androidx.work.runtime)
            // The push service (`AndroidPushNotifications`): FCM, started by hand from the deployment's values.
            implementation(libs.firebase.messaging)
            // The Play Install Referrer: the invite a Play install from the event page carried (`join-event`).
            implementation(libs.installreferrer)
            // The `Databases` adapter: SQLDelight over the platform's SQLite, and the open helper it is handed.
            implementation(libs.sqldelight.driver.android)
            implementation(libs.androidx.sqlite.framework)
            // The backend port's HTTP engine (`AndroidHttpClient`); `HttpBackend` itself is `:adapter:generic:app`'s.
            api(libs.ktor.client.core)
            implementation(libs.ktor.client.okhttp)
        }
        getByName("androidDeviceTest").dependencies {
            implementation(project(":test:contracts"))
            // The storage services' contracts run through the services over these adapters.
            implementation(project(":domain:services"))
            implementation(libs.kotlinx.datetime)
            implementation(libs.coroutines.test)
            implementation(libs.androidx.work.testing)
            // The Ui contract's binding: the real screen over a real status container, tapped through a Compose UI
            // test hosted in the manifest artifact's `ComponentActivity`.
            implementation(project(":domain:presentation"))
            implementation(project(":domain:feature"))
            implementation(project(":ui:screens"))
            implementation(project(":ui:components"))
            implementation(libs.compose.ui.test)
            implementation(libs.compose.components.resources)
            implementation(libs.orbit.core)
            implementation(libs.androidx.compose.ui.test.manifest)
        }
    }
}
