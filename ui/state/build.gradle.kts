plugins {
    alias(libs.plugins.kotlin.multiplatform)
    // The allowed targets, declared once (`docs/architecture.md`, "The core and its layers").
    id("snapsync.targets")
    alias(libs.plugins.kotlin.serialization)
}

// The screen's vocabulary (`docs/architecture.md`, "The screen's vocabulary"): what the status screen shows
// (`UiState`, display values only) and what a person can do on it (`UiIntent`) — the one module presentation and the
// screens both name. It references nothing project-internal: a screen sees no domain type, and presentation maps the
// domain into these.

kotlin {
    sourceSets {
        commonMain.dependencies {
            // Display values only: dates are kotlinx-datetime values, and the state crosses the control
            // channel as JSON.
            api(libs.kotlinx.datetime)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

// Coverage (`docs/architecture.md`, "Coverage").
coverage {
    unit()
    // → 91/68 in phase 31, once the polling tests waited on outcome events instead.
    // → 94/72 in phase 4.9 (status and presentation): the joined layer's and the switch's new optional facts
    // (`accessFix`, `PendingSwitch.service`) add their generated serializers' default branches, which the suite
    // never decodes. No test was lost. Measured 72.93 %.
    // → 93/72 when the shown invite became a secret type (4.10): its masked print, which exists so that a logged
    // screen state carries no key, is never reached by a member's day — by design. No test was lost. Measured
    // 93.76 %.
    integration(instructions = 93, branches = 72)
}
