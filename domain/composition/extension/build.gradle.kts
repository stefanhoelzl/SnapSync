plugins {
    // A core layer (`snapsync.layer`): targets, the shared test dependencies.
    id("snapsync.layer")
}

// The core's composition layer, the upload extension's half (`docs/architecture.md`, "The composition layer"): the
// extension process's ports and graph — only what an upload pass needs, its restrictions held by type. WIRING ONLY:
// graph classes of `val`s built by constructor calls (`CompositionIsWiringTest`). It never links presentation, the
// query layer or the screen's vocabulary, and no old zone; nothing it links opts into `@AppProcessOnly`
// (`ExtensionSafetyTest`).

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
            implementation(project(":domain:entrypoints"))
            implementation(libs.coroutines.core)
        }
    }
}
