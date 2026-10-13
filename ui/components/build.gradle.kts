plugins {
    alias(libs.plugins.kotlin.multiplatform)
    // The allowed targets, declared once (`docs/architecture.md`, "Zones inside the core").
    id("snapsync.targets")
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
}

kotlin {
    jvm {
        testRuns["test"].executionTask.configure {
            // Skiko loads native libs via a restricted method; future JDKs block it by default.
            jvmArgs("--enable-native-access=ALL-UNNAMED")
            // Compose's test renderer draws offscreen; headless skips AWT's display probe so the
            // tests need no X server on Linux (no Xvfb, no stale-lock hang).
            jvmArgs("-Djava.awt.headless=true")
            // One locale for every run (`docs/architecture.md`, "Localization"): the strings resolve to the base
            // language and a date reads as en-GB writes it, whatever machine runs the suite.
            jvmArgs("-Duser.language=en", "-Duser.country=GB")
        }
    }
    sourceSets {
        commonMain.dependencies {
            // The screen's vocabulary in App* signatures (`Arrow`, `RangeChoice`, the range calendar's `PickerView`):
            // the values presentation reduces into and this skin draws. Never the domain.
            api(project(":ui:state"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.components.resources)
            // The ONLY module allowed to depend on Material 3 (spec: docs/architecture.md).
            implementation(libs.compose.material3)
            // Material icon glyphs (e.g. the leave action's Logout). Contained here like Material 3 —
            // the `Icons.*` import never leaves this module; no `App*` signature carries a glyph type.
            implementation(libs.compose.material.icons.extended)
            // QR rendering for AppQrCode — Compose-MP-native, contained to this module like Material 3
            // (the qrose import never leaves this module; no `App*` signature carries a QR type).
            implementation(libs.qrose)
            // Plain multiplatform date-time value for AppDateTimeField's semantic signature
            // (LocalDateTime is a data/meaning type, not a Material 3 type — the containment rule is intact).
            implementation(libs.kotlinx.datetime)
        }
        // jvmTest only: the offscreen Compose renderer for asserting a component's assistive-tech
        // semantics (roles, labels, disabled state) — the design-system components are otherwise
        // exercised through :ui:screens, but the picker dialog's internals warrant a direct probe.
        jvmTest.dependencies {
            implementation(kotlin("test"))
            // The JVM's real date formatting (`JvmDateFormatting`): a label is asserted as a locale writes it, and how
            // a date reads is the platform's, behind the `DateFormatting` port — never this module's.
            implementation(project(":adapter:generic:app"))
            implementation(libs.compose.ui.test.junit4)
            implementation(compose.desktop.currentOs)
        }
    }
}

// The module's strings (`docs/architecture.md`, "Localization"): `src/commonMain/composeResources/values/`
// is the base language, `values-<lang>/` a translation. The generated `Res` is public only so `:ui:screens`' tests
// can name the words a component shows; a screen passes its OWN words to a component, never borrows these.
compose.resources {
    packageOfResClass = "app.snapsync.ui.components.resources"
    publicResClass = true
}
