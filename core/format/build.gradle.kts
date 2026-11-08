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
            implementation(project(":core:domain"))
            // A timestamp's calendar fields in UTC.
            implementation(libs.kotlinx.datetime)
            // The fragment form's JSON payload, read as a tree.
            implementation(libs.kotlinx.serialization.json)
        }
    }
}

// Coverage (`docs/architecture.md`, "Coverage").
coverage {
    unit()
    // Six branches, so one is 17 points: an undated photo's capture text (the library's empty date) is the unit
    // suite's — no integration world adds an undated photo. 95/83 → 80/64 in phase 4.2, when the invite link's
    // text moved in: a Play install's referrer (Android's, which the JVM host cannot deliver) and every way a
    // fragment can be malformed are the unit suite's. Instructions back to the goal once the suite delivered a Play
    // install's referrer (`InstallReferrerIntegrationTest`); branches 64 → 78 with it. → 90/70 in phase 4.3
    // (encryption), when the encrypted file format moved in from model: every way a file's head is not this
    // format, a head's parts of another length and HKDF's bounds are the unit suite's (`EncryptedFileFormatTest`)
    // — a member's day only ever reads whole files; → 89/68 with the end-of-file rule and the length check the
    // cipher hands its keys on through (`segmentOf`, `ofLength`), whose refusals are the unit suite's too.
    // → 84/66 in phase 4.4a (selection), when the asset id's encoding moved in from services (`AssetIdText`): the
    // characters a platform id may hold beyond letters, digits, `-` and `/` — and their decoding — are the unit
    // suite's (`AssetIdTextTest`); no integration world's photo carries one. Measured 84.36 / 66.23 %.
    // → 85 in phase 38 (missing levers and world events). Measured 85.16 / 66.87 %, two runs.
    // Branches → 65 in phase 4.10 (diagnostics), when the memory trail's codec moved in from model: every way a
    // trail file is not one is the unit suite's (`FootprintTrailTextTest`) — a member's day reads only its own.
    // Measured 65.86 %.
    // → 84/58 in phase 5 (clean-up), when the legacy membership's decoder and the push payload's codec moved in
    // from model: every way a 0.4 file does not read and every malformed push are the unit suite's
    // (`LegacyMembershipTextTest`, `PushPayloadTextTest`) — an updated device holds one good file, a push names its
    // event. No test was lost. Measured 84.25 / 58.83 %.
    integration(instructions = 84, branches = 58)
}
