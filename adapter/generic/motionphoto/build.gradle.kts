// `:adapter:generic:motionphoto` (`docs/architecture.md`): Google's Motion Photo format — a JPEG whose XMP describes a
// video appended at its end — written by the Android import adapter (an iPhone Live Photo saved as one) and read by
// the iOS one (an Android motion photo taken apart into a Live Photo). One module rather than a copy per adapter,
// because both platforms must agree on one file format: what one writes, the other has to find again.
//
// It references nothing project-internal: a format codec needs nothing of the app, and the adapters that link it
// see no core code through it.

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    // The allowed targets, declared once (`docs/architecture.md`, "Zones inside the core").
    id("snapsync.targets")
    // The zero coverage gate (`docs/architecture.md`, "Coverage"): no instruction and no branch missed.
    id("snapsync.coverage-zero")
}

kotlin {
    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
