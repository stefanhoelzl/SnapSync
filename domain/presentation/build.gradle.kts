plugins {
    alias(libs.plugins.kotlin.multiplatform)
    // The allowed targets, declared once (`docs/architecture.md`, "Zones inside the core").
    id("snapsync.targets")
    // `UiState` is `@Serializable` so the dev/test control channel can serve the REAL reduced state
    // rather than a hand-written mirror of it (`:test:rig`). Annotations only — the encoder is
    // compiler-generated, so there is no projection that could drift from what the screen renders,
    // which is what lets the rig hold no tests. A rig-side DTO was the alternative and was rejected
    // for exactly that reason.
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        // The core's `presentation` zone (`docs/architecture.md`, "Zones inside the core"): the UI-state
        // reduction. Every edge is `implementation()` — a consumer that needs `model/`, `feature/`, Orbit or
        // kotlinx-datetime declares it, rather than receiving it from here. Of `feature/`, only the `readmodel`
        // packages may be named (the read-model import gate, `docs/architecture.md`).
        commonMain.dependencies {
            implementation(project(":domain:model"))
            implementation(project(":domain:domain"))
            implementation(project(":domain:feature"))
            // The screen's vocabulary this zone reduces into (`UiState`) and hears (`UiIntent`).
            implementation(project(":ui:state"))
            implementation(libs.orbit.core)
            implementation(libs.kotlinx.datetime)
            implementation(libs.coroutines.core)
            // `@Serializable` on the reduction's own serializable types (see the plugin note above).
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.orbit.test)
            implementation(libs.coroutines.test)
        }
    }
}
