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
            implementation(project(":core:composition:process"))
            implementation(project(":core:domain"))
            implementation(project(":core:inbound"))
            implementation(project(":core:ports"))
            implementation(project(":core:scheduling"))
            implementation(project(":core:data"))
            implementation(project(":core:application"))
            implementation(project(":core:entrypoints"))
            implementation(libs.coroutines.core)
        }
    }
}

// Coverage (`docs/architecture.md`, "Coverage"). The composition layer: wiring, not unit-tested by law ("The
// composition layer") — smoke-tested by the integration surface, and driven by the mocks' suites: the upload extension
// over its entry port (`ExtensionEntryTest`).
coverage {
    unit(alsoCoveredBy = listOf(":test:integration", ":adapter:generic:mock"))
    integration(instructions = 100, branches = 100)
}
