// `:app:android` — the Android app's composition root, what `:app:ios` is to the phone: an `Application` that composes
// the app once, in its one process (`snapSyncProcess`, then `snapSyncHost` — `SnapSyncRoot`), and the activity that
// pulls the screen. Wiring only, gated as a shell (`detektAppShell`, `KotlinShellGuardTest`); no test source set.
//
// A build WITHOUT `-Psnapsync.rig=true` composes every real Android adapter (`src/prod`), with no crash reporter until
// phase 5. A rig build composes over an adapter choice of real and mocked systems, with the control channel served
// in-process (`test/rig/src/android-hook`), reached over `adb forward`.
import app.snapsync.buildlogic.EntryNamesTask
import app.snapsync.buildlogic.rigEnabled
import com.android.build.api.artifact.ScopedArtifact
import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.ScopedArtifacts

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    // The rig switch, `rigEnabled` (`-Psnapsync.rig=true`).
    id("snapsync.rig")
}

// The resolved deployment the app talks to (`docs/deployment.md`): `:domain:format` runs the resolver at configuration
// time (it generates LINK_ORIGIN from the same resolution), so this project reads what that run rendered.
evaluationDependsOn(":domain:format")

val deployment: Map<String, String> = rootProject.layout.projectDirectory.file("build/deployment.properties").asFile
    .readLines()
    .filterNot { it.isBlank() || it.startsWith("#") }
    .associate { line -> line.substringBefore('=') to line.substringAfter('=') }

// The values of the resolved deployment NOBODY REVIEWED (`docs/deployment.md`): the crash-reporting destination and
// its environment. The resolver renders them to JSON — a grammar that escapes — and never to `deployment.properties`,
// whose values are interpolated raw; the DSN once reached a raw grammar and shipped mute builds. The DSN is present
// only for a distributed channel: absence is the off-switch.
val unreviewed: Map<String, String> = rootProject.layout.projectDirectory.file("build/deployment.json").asFile
    .let { groovy.json.JsonSlurper().parse(it) as Map<*, *> }
    .entries.associate { (key, value) -> key.toString() to value.toString() }

/** [value] as a Java string literal — escaped, since a `buildConfigField` is pasted into generated source as it is. */
fun javaString(value: String): String {
    require(value.none { it < ' ' }) { "a deployment value carries a control character" }
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}

// The version this build declares to the backend and to the store: a delivering
// run's `-Psnapsync.versionName`, the marketing version `scripts/marketing-version.py` computes for BOTH stores;
// without it, the SAME marketing-version floor every iOS dev build carries (`Config.xcconfig`), which the api's
// `MIN_APP_VERSION` is pinned to stay at or below.
val marketingVersionFloor: String = rootProject.layout.projectDirectory.file("iosApp/Configuration/Config.xcconfig")
    .asFile.readLines()
    .single { it.startsWith("MARKETING_VERSION") }
    .substringAfter('=').trim()
val marketingVersion: String = providers.gradleProperty("snapsync.versionName").getOrElse(marketingVersionFloor)
    .also { require(Regex("""\d+\.\d+""").matches(it)) { "snapsync.versionName '$it' is not two-part X.Y" } }

// The build number: CI's `run_number + BUILD_NUMBER_OFFSET` (`ci.yml`), the SAME number the run's iOS build carries as
// `CFBundleVersion`, so one number names one build in both stores — and is the crash reports' `dist`, which picks the
// `r8-mapping-<build>` artifact a stack trace is retraced with. A local build is 1.
val buildNumber: Int = providers.gradleProperty("snapsync.versionCode").map(String::toInt).getOrElse(1)

android {
    namespace = "app.snapsync.android"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig {
        applicationId = "app.snapsync"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = buildNumber
        versionName = marketingVersion
        val uploadBase = requireNotNull(deployment["uploadBase"]) { "the resolved deployment rendered no uploadBase" }
        buildConfigField("String", "UPLOAD_BASE", "\"$uploadBase\"")
        buildConfigField("String", "APP_VERSION", "\"$marketingVersion\"")
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
        // The app's Google Play page (the update notice's one remedy). Empty
        // until the listing is public (production launch): the notice then offers no store at all.
        val playStoreUrl = requireNotNull(deployment["playStoreUrl"]) { "the deployment rendered no playStoreUrl" }
        buildConfigField("String", "PLAY_STORE_URL", "\"$playStoreUrl\"")
        // Where this build reports crashes: empty on every build but a distributed
        // one, and then nothing starts. The environment its reports are filed under, derived from the same channel.
        buildConfigField("String", "SENTRY_DSN", javaString(unreviewed["sentryDsn"].orEmpty()))
        buildConfigField("String", "SENTRY_ENVIRONMENT", javaString(requireNotNull(unreviewed["sentryEnvironment"])))
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
            // R8 and resource shrinking on EVERY release, the store build's own: CI's `android-build` builds the plain
            // release, and `journeys (android)` RUNS the rig release — so every journey exercises R8 output, where a
            // class reached only by name (the manifest, a service loader, reflection) breaks with a green build.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // A rig release is test equipment, never a store build: the debug key signs it so it installs on an
            // emulator. The plain release stays unsigned here — `ci.yml`'s `android-deliver` signs the bundle with
            // the store's upload key, so no build of this file ever needs it.
            if (rigEnabled) signingConfig = signingConfigs.getByName("debug")
        }
    }
    // ---- Android Lint, gating `./gradlew build` ------------------------------------------------------------------
    //
    // Play's own release recommendations (outdated SDKs, edge-to-edge) have no API, so the build runs the nearest local
    // equivalent instead: lint over the store build's variant and every module it links (`checkDependencies`), where
    // any warning fails. No baseline — a finding is fixed, or suppressed AT ITS SITE with the reason beside it.
    lint {
        checkDependencies = true
        warningsAsErrors = true
        abortOnError = true
        // targetSdk deliberately tracks Play's current requirement (`libs.versions.toml`), not the newest SDK.
        disable += "OldTargetApi"
        // No lint-vital pass on assembling the release: its fatal checks are a subset of `lintRelease`, which `check`
        // runs over the same variant with every warning failing (24 s of every build, 2026-10-13).
        checkReleaseBuilds = false
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

dependencies {
    // Both together, or neither: the contributed hook and the module it names cannot be half-present.
    if (rigEnabled) implementation(project(":test:rig"))
    implementation(project(":domain:model"))
    implementation(project(":domain:ports"))
    implementation(project(":domain:inbound"))
    implementation(project(":domain:feature"))
    implementation(project(":domain:compose"))
    implementation(project(":domain:services"))
    implementation(project(":domain:presentation"))
    // The shared host composition: the core and the status host over it (`snapSyncHost`).
    implementation(project(":domain:host"))
    implementation(project(":adapter:generic:app"))
    implementation(project(":adapter:android"))
    // The screen's date formatting the root builds over the `DateFormatting` port, for the UI adapter (`ScreenDates`).
    implementation(project(":ui:components"))
    implementation(project(":ui:state"))
    // The crash-reporting seat both platforms share.
    implementation(project(":adapter:generic:sentry"))
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)
    implementation(libs.kermit)
    // The cutoff formatter's zone, and the status host's Orbit supertype — neither exported by the zones naming them.
    implementation(libs.kotlinx.datetime)
    implementation(libs.orbit.core)
    implementation(libs.compose.runtime)
    implementation(libs.androidx.activity.compose)
    constraints {
        implementation(libs.androidx.fragment) {
            because("play-services-base pulls 1.1.0, which Play flags as deprecated")
        }
    }
}

// The lint gate above runs with the canonical check, over the release variant — the one the store receives — and
// ONLY over it: AGP also hangs its default `lint` (the debug variant, with its unit and device test sources) on
// `check`, and this app has no debug-only or test source, so that pass re-read what `lintRelease` gates (~40 s,
// 2026-10-13).
tasks.named("check") {
    dependsOn("lintRelease")
    setDependsOn(dependsOn.filterNot { it == "lint" || (it as? Named)?.name == "lint" })
}

// No component of the merged app starts before the device's first unlock (`AndroidManifest.xml`): the `ProcessInfo`
// adapter answers protected data available on that ground, so a library update that brings a direct-boot-aware
// component back must fail here rather than make that answer untrue.
val verifyNoDirectBoot = tasks.register("verifyNoDirectBoot") {
    group = "verification"
    description = "Fails when the merged release manifest declares a direct-boot-aware component."
    val merged = layout.buildDirectory.file(
        "intermediates/merged_manifest/release/processReleaseMainManifest/AndroidManifest.xml",
    )
    dependsOn("processReleaseMainManifest")
    inputs.file(merged)
    doLast {
        val text = merged.get().asFile.readText()
        val aware = Regex("""<(\w+)\b[^>]*android:name="([^"]+)"[^>]*android:directBootAware="true"""")
            .findAll(text).map { "${it.groupValues[1]} ${it.groupValues[2]}" }.toList()
        check(aware.isEmpty()) {
            "the merged manifest declares direct-boot-aware components; declare each otherwise in " +
                "AndroidManifest.xml:\n  " +
                aware.joinToString("\n  ")
        }
    }
}
tasks.named("check") { dependsOn(verifyNoDirectBoot) }

// Every entry point and tap intent tells its own class name in the release R8 built (`proguard-rules.pro`): neither
// renamed nor merged, and a nested intent still carrying the annotation `simpleName` reads. The classes are those R8
// was given, so one the rules miss is caught, not skipped; no device needed.
val verifyEntryNames = tasks.register<EntryNamesTask>("verifyEntryNames") {
    group = "verification"
    description = "Fails when the release renames or merges an entry point or tap intent, or loses a nested one's name."
    roots.set(
        listOf("BackgroundEntryPoint", "GrantedEntryPoint", "InboundEntryPoint", "AnsweredEntryPoint")
            .map { "app.snapsync.scheduling.$it" } + "app.snapsync.ui.state.UiIntent",
    )
    val buildTools = "build-tools/${android.buildToolsVersion}"
    dexdump.set(androidComponents.sdkComponents.sdkDirectory.map { it.file("$buildTools/dexdump") })
    verdict.set(layout.buildDirectory.file("reports/entry-names.txt"))
}
androidComponents.onVariants(androidComponents.selector().withBuildType("release")) { variant ->
    variant.artifacts.forScope(ScopedArtifacts.Scope.ALL).use(verifyEntryNames)
        .toGet(ScopedArtifact.CLASSES, EntryNamesTask::jars, EntryNamesTask::directories)
    verifyEntryNames.configure {
        mapping.set(variant.artifacts.get(SingleArtifact.OBFUSCATION_MAPPING_FILE))
        apk.set(variant.artifacts.get(SingleArtifact.APK))
    }
}
tasks.named("check") { dependsOn(verifyEntryNames) }
