plugins {
    // A core layer (`snapsync.layer`): targets, the shared test dependencies.
    id("snapsync.layer")
    // The deployment's values compiled in (`LINK_ORIGIN`).
    id("snapsync.deployment")
}

// The core's `format` module (`docs/patterns.md`, "Formats live at the boundary"): the text a value takes on the wire
// and in a store, written once per format — a timestamp's canonical text, an invite link's, an event key's. It holds
// formats and nothing else, so a zone that needs one gains no other reach: a dependency on it hands over no DTO, no
// port and no store. It depends on the domain only, for the values it spells.

// The invite link's origin, generated from the RESOLVED DEPLOYMENT (`docs/deployment.md`) by `snapsync.deployment`, so
// the app, the backend, the iOS project and the site all derive it from one declared value rather than each holding a
// copy.
deployment {
    generateLinkOrigin(packageName = "app.snapsync.format")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":domain:domain"))
            // A timestamp's calendar fields in UTC.
            implementation(libs.kotlinx.datetime)
            // The fragment form's JSON payload, read as a tree.
            implementation(libs.kotlinx.serialization.json)
        }
    }
}
