plugins {
    alias(libs.plugins.kotlin.multiplatform)
    // The allowed targets, declared once (`docs/architecture.md`, "Zones inside the core").
    id("snapsync.targets")
    alias(libs.plugins.kotlin.serialization)
    // The zero coverage gate (`docs/architecture.md`, "Coverage"): no instruction and no branch missed.
    id("snapsync.coverage-zero")
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
