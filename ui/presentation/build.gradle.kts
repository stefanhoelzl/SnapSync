plugins {
    alias(libs.plugins.kotlin.multiplatform)
    // `UiState` is `@Serializable` so the dev/test control channel can serve the REAL reduced state
    // rather than a hand-written mirror of it (`:test:rig`). Annotations only — the encoder is
    // compiler-generated, so there is no projection that could drift from what the screen renders,
    // which is what lets the rig hold no tests. A rig-side DTO was the alternative and was rejected
    // for exactly that reason.
    alias(libs.plugins.kotlin.serialization)
}

// The FORGE preset table (`ForgeStatusHost.kt`) is compiled in ONLY under `-Psnapsync.forge=true`.
//
// It exists to render marketing screenshots and is used by nothing else — not the desktop forge harness,
// which forges through its own control panel, and not the app. Leaving it in `commonMain` meant the preset
// table and the states it fabricates shipped in every binary, reachable in principle by anything that could
// call `forgeStatusHost`. Now the forge binary is the only thing that compiles it.
val forgeEnabled = providers.gradleProperty("snapsync.forge").map(String::toBoolean).getOrElse(false)

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
    jvm()
    iosArm64()
    iosSimulatorArm64()
    sourceSets {
        if (forgeEnabled) {
            commonMain { kotlin.srcDir("src/forge/kotlin") }
            // Gated WITH its source, not left behind. `ForgeStatusHostTest` asserts the presets drive the
            // real reduction to the frames they claim — which is the property the App Store listing rests
            // on (`ios-appstore-metadata`), so it must run wherever the presets compile.
            commonTest { kotlin.srcDir("src/forgeTest/kotlin") }
        }
        commonMain.dependencies {
            api(project(":domain"))
            // The container consumes the config seam + decoder (onOpenUrl), and ConfigSource/
            // ConfigStore appear in its constructor — so they surface in this module's API.
            // The create-event seams (CreationStatusSource/EventCreator) folded into the reduction and
            // the container's constructor — so they surface in this module's API.
            api(libs.orbit.core)
            // Capability `photo-selection-policy`: LocalDateTime appears in CutoffFormatter's signature (used
            // by the join screen in :ui:screens), so it is part of this module's API.
            api(libs.kotlinx.datetime)
            // `@Serializable` on `UiState` (see the plugin note above). `:domain` keeps its own
            // serialization dep as `implementation`, so it does not reach here transitively.
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.orbit.test)
            implementation(libs.coroutines.test)
        }
    }
}
