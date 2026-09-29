// `:app:android` — the Android app's composition root, what `:app:ios` is to the phone: an `Application` that composes
// the app once, in its one process (`snapSyncProcess`, then `snapSyncHost` — `SnapSyncRoot`), and the activity that
// pulls the screen. Wiring only, gated as a shell (`detektAppShell`, `KotlinShellGuardTest`); no test source set.
//
// NO Android adapter exists yet for any system but the screen and its foreground life, so a build WITHOUT
// `-Psnapsync.rig=true` compiles and links, and refuses at start (`src/prod`), naming why. A rig build composes over
// the mocks with the control channel served in-process (`test/rig/src/android-hook`), reached over `adb forward`.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "app.snapsync.android"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig {
        applicationId = "app.snapsync"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(libs.versions.android.jvmTarget.get())
        targetCompatibility = JavaVersion.toVersion(libs.versions.android.jvmTarget.get())
    }
    buildTypes {
        release {
            // R8 over the whole app, as a store build will run it — on the rig build, which links the whole graph
            // (the `android-emulator` job builds it). The plain release refuses at start, so R8 would strip it to
            // nothing and prove nothing; the store build switches it on with the adapters that give it something
            // to keep.
            isMinifyEnabled = rigEnabled
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    // ---- The control channel (`:test:rig`), contained at COMPILE TIME --------------------------------------------
    //
    // `-Psnapsync.rig=true` adds BOTH the module and the source directory it contributes; without the property it
    // adds NEITHER, so a production build contains no rig source at all (`docs/architecture.md`, "A build-time-only
    // module is contained by compilation"). The contributed directory is in the root build's `appShellSources`.
    sourceSets.getByName("main").kotlin.directories.add(
        if (rigEnabled) "../../test/rig/src/android-hook/kotlin" else "src/prod/kotlin",
    )
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
    compilerOptions.jvmTarget.set(
        org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget(libs.versions.android.jvmTarget.get()),
    )
}

val rigEnabled: Boolean get() = providers.gradleProperty("snapsync.rig").map(String::toBoolean).getOrElse(false)

dependencies {
    // Both together, or neither: the contributed hook and the module it names cannot be half-present.
    if (rigEnabled) implementation(project(":test:rig"))
    implementation(project(":domain:model"))
    implementation(project(":domain:ports"))
    implementation(project(":domain:feature"))
    implementation(project(":domain:compose"))
    implementation(project(":domain:services"))
    implementation(project(":domain:presentation"))
    // The shared host composition: the core and the status host over it (`snapSyncHost`).
    implementation(project(":domain:host"))
    implementation(project(":adapter:generic:app"))
    implementation(project(":adapter:android"))
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)
    implementation(libs.kermit)
    // The cutoff formatter's zone, and the status host's Orbit supertype — neither exported by the zones naming them.
    implementation(libs.kotlinx.datetime)
    implementation(libs.orbit.core)
    implementation(libs.compose.runtime)
    implementation(libs.androidx.activity.compose)
}
