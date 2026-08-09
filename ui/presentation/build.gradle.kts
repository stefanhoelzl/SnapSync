plugins {
    alias(libs.plugins.kotlin.multiplatform)
    // `UiState` is `@Serializable` so the dev/test control channel can serve the REAL reduced state
    // rather than a hand-written mirror of it (`:test:rig`). Annotations only — the encoder is
    // compiler-generated, so there is no projection that could drift from what the screen renders,
    // which is what lets the rig hold no tests. A rig-side DTO was the alternative and was rejected
    // for exactly that reason.
    alias(libs.plugins.kotlin.serialization)
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
