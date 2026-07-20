plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
    jvm {
        testRuns["test"].executionTask.configure {
            // Skiko loads native libs via a restricted method; future JDKs block it by default.
            jvmArgs("--enable-native-access=ALL-UNNAMED")
            // Compose's test renderer draws offscreen; headless skips AWT's display probe so the
            // tests need no X server on Linux (no Xvfb, no stale-lock hang).
            jvmArgs("-Djava.awt.headless=true")
        }
    }
    iosArm64()
    iosSimulatorArm64()
    sourceSets {
        commonMain.dependencies {
            // Shared sync vocabulary in App* signatures (`model/`'s Arrow — the step-9 Arrow/ArrowLevel
            // unification): the ONE enum both presentation's reduction and this skin render from.
            api(project(":domain"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            // The ONLY module allowed to depend on Material 3 (spec: design-system).
            implementation(compose.material3)
            // Material icon glyphs (e.g. the leave action's Logout). Contained here like Material 3 —
            // the `Icons.*` import never leaves this module; no `App*` signature carries a glyph type.
            implementation(compose.materialIconsExtended)
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
            implementation(compose.desktop.uiTestJUnit4)
            implementation(compose.desktop.currentOs)
        }
    }
}
