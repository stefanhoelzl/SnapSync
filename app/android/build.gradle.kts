// `:app:android` — the Android app's composition root, what `:app:ios` is to the phone: an `Application` that composes
// the app once, in its one process (`snapSyncProcess`, then `snapSyncHost` — `SnapSyncRoot`), and the activity that
// pulls the screen. Wiring only, gated as a shell (`detektAppShell`, `KotlinShellGuardTest`); no test source set.
//
// A build WITHOUT `-Psnapsync.rig=true` composes every real Android adapter (`src/prod`), with no crash reporter until
// phase 5. A rig build composes over an adapter choice of real and mocked systems, with the control channel served
// in-process (`test/rig/src/android-hook`), reached over `adb forward`.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// The resolved deployment the app talks to (`docs/deployment.md`): `:domain:model` runs the resolver at configuration
// time (it generates LINK_ORIGIN from the same resolution), so this project reads what that run rendered.
evaluationDependsOn(":domain:model")

val deployment: Map<String, String> = rootProject.layout.projectDirectory.file("build/deployment.properties").asFile
    .readLines()
    .filterNot { it.isBlank() || it.startsWith("#") }
    .associate { line -> line.substringBefore('=') to line.substringAfter('=') }

// The version this build declares to the backend (capability `app-update-required`): the SAME marketing-version floor
// every iOS dev build carries (`Config.xcconfig`), which the api's `MIN_APP_VERSION` is pinned to stay at or below.
// Android derives no release versions yet — the store build does (phase 5) — so the floor is the honest declaration.
val marketingVersionFloor: String = rootProject.layout.projectDirectory.file("iosApp/Configuration/Config.xcconfig")
    .asFile.readLines()
    .single { it.startsWith("MARKETING_VERSION") }
    .substringAfter('=').trim()

android {
    namespace = "app.snapsync.android"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig {
        applicationId = "app.snapsync"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = marketingVersionFloor
        val uploadBase = requireNotNull(deployment["uploadBase"]) { "the resolved deployment rendered no uploadBase" }
        buildConfigField("String", "UPLOAD_BASE", "\"$uploadBase\"")
        buildConfigField("String", "APP_VERSION", "\"$marketingVersionFloor\"")
        // The Firebase project the push service starts from (`docs/deployment.md`) — rendered, never a
        // google-services.json. Empty until the project exists: the app then starts no Firebase and gets no push.
        listOf(
            "FIREBASE_PROJECT_ID" to "firebaseProjectId",
            "FIREBASE_APPLICATION_ID" to "firebaseApplicationId",
            "FIREBASE_API_KEY" to "firebaseApiKey",
            "FIREBASE_SENDER_ID" to "firebaseSenderId",
        ).forEach { (field, key) ->
            val value = requireNotNull(deployment[key]) { "the resolved deployment rendered no $key" }
            buildConfigField("String", field, "\"$value\"")
        }
        // The event link's host — the resolved deployment's domain without a port (an intent filter with no port
        // matches any, and LINK_ORIGIN carries the local rig's).
        manifestPlaceholders["linkHost"] = requireNotNull(deployment["domain"]).substringBefore(':')
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(libs.versions.android.jvmTarget.get())
        targetCompatibility = JavaVersion.toVersion(libs.versions.android.jvmTarget.get())
    }
    buildTypes {
        release {
            // R8 over the whole app, as a store build will run it — on the rig build, which links the whole graph
            // (CI's `android-build` job builds it). The plain release composes the same adapters now; switching R8 on
            // for it, with the keep rules Firebase and Ktor need, is the store build's (phase 5).
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
    // The rig's cleartext-to-loopback exception, which reaches the local api: its resource and the manifest overlay
    // naming it, both only under the property.
    if (rigEnabled) sourceSets.getByName("main").res.directories.add("../../test/rig/src/android-hook/res")
}

androidComponents {
    onVariants { variant ->
        if (rigEnabled) {
            variant.sources.manifests.addStaticManifestFile("../../test/rig/src/android-hook/AndroidManifest.xml")
        }
    }
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
