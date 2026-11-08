plugins {
    // A core layer (`snapsync.layer`): targets, the shared test dependencies.
    id("snapsync.layer")
}

// The core's composition layer, the app's half (`docs/architecture.md`, "The composition layer"): the app process's
// ports and graph over the shared half — every layer, presentation and the screen's vocabulary included. WIRING ONLY:
// graph classes of `val`s built by constructor calls, and one composing function (`CompositionIsWiringTest`).

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":domain:composition:process"))
            implementation(project(":domain:domain"))
            implementation(project(":domain:inbound"))
            implementation(project(":domain:ports"))
            implementation(project(":domain:scheduling"))
            implementation(project(":domain:data"))
            implementation(project(":domain:application"))
            implementation(project(":domain:query"))
            implementation(project(":domain:entrypoints"))
            implementation(project(":domain:presentation"))
            implementation(project(":domain:format"))
            // The screen's vocabulary the app's `Ui` port is bound to (`Ui<UiState, UiIntent>`).
            implementation(project(":ui:state"))
            implementation(libs.coroutines.core)
        }
    }
}
