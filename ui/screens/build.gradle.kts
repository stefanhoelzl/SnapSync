plugins {
    alias(libs.plugins.kotlin.multiplatform)
    // The allowed targets, declared once (`docs/architecture.md`, "Zones inside the core").
    id("snapsync.targets")
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
    // Coverage measurement and its zero gate (`docs/architecture.md`, "Coverage"). Applied here rather than in a
    // `subprojects {}` block so the instrumented set is readable per module.
    id("snapsync.coverage-zero")
    // The text the OS shows outside the screens, generated from this module's strings (`docs/architecture.md`,
    // "Localization").
    id("snapsync.native-strings")
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
            api(project(":domain:model"))
            api(project(":domain:feature"))
            api(project(":domain:presentation"))
            // Declared here rather than received: presentation's edges are `implementation()` only, so it
            // exports neither. `LocalDateTime` appears in the join/create screens' signatures; the container
            // host is an Orbit `ContainerHost`.
            api(libs.kotlinx.datetime)
            implementation(libs.orbit.core)
            implementation(project(":ui:components"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.components.resources)
        }
        // The screen tests live in commonTest, which CI runs on the JVM only (`build`; offscreen —
        // see the jvm block above). The simulator runs platform-bound tests only (`docs/testing.md`,
        // "Where each test runs"), so this suite never sees the Compose backend iOS renders these
        // screens through; only the simulator app's runs (journeys, screenshots) render that one.
        commonTest.dependencies {
            implementation(kotlin("test"))
            // The multiplatform `runComposeUiTest` API (no JUnit4 rule — that artifact is JVM-only).
            implementation(libs.compose.ui.test)
        }
        jvmTest.dependencies {
            // The JVM's real date formatting (`JvmDateFormatting`), the one the screen tests render through: a date
            // is asserted as en-GB writes it, and how it reads is the platform's, behind the `DateFormatting` port.
            implementation(project(":adapter:generic:app"))
            // Skiko's desktop native binaries — the JVM renderer the offscreen scene draws into.
            implementation(compose.desktop.currentOs)
        }
    }
}

// The module's strings (`docs/architecture.md`, "Localization"): `src/commonMain/composeResources/values/`
// is the base language, `values-<lang>/` a translation. The generated `Res` is public only so tests can name the
// words a screen shows (`:adapter:android`'s Ui contract binding taps the real screen by them); production code passes
// a screen its state, never borrows its words.
compose.resources {
    packageOfResClass = "app.snapsync.ui.resources"
    publicResClass = true
}

// The languages the app ships (`docs/architecture.md`, "Localization"): the first is the base, in `values/`. Adding
// one is a `values-<lang>/strings.xml` in BOTH UI modules plus its tag here; `./gradlew nativeStrings` then writes
// what each OS needs to offer it.
nativeStrings {
    locales.set(listOf("en", "de"))
    otherResources.add(rootProject.file("ui/components/src/commonMain/composeResources"))
}
