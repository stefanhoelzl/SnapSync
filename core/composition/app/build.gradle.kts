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
            implementation(project(":core:composition:process"))
            implementation(project(":core:domain"))
            implementation(project(":core:inbound"))
            implementation(project(":core:ports"))
            implementation(project(":core:scheduling"))
            implementation(project(":core:data"))
            implementation(project(":core:application"))
            implementation(project(":core:query"))
            implementation(project(":core:entrypoints"))
            implementation(project(":core:presentation"))
            implementation(project(":core:format"))
            // The screen's vocabulary the app's `Ui` port is bound to (`Ui<UiState, UiIntent>`).
            implementation(project(":ui:state"))
            implementation(libs.coroutines.core)
        }
    }
}
