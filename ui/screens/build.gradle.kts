import kotlinx.kover.gradle.plugin.dsl.CoverageUnit
import kotlinx.kover.gradle.plugin.dsl.GroupingEntityType

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    // The allowed targets, declared once (`docs/architecture.md`, "Zones inside the core").
    id("snapsync.targets")
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
    // Coverage measurement (`docs/architecture.md`). Applied here rather than in a
    // `subprojects {}` block so the instrumented set is readable per module.
    alias(libs.plugins.kover)
}

kotlin {
    jvm {
        testRuns["test"].executionTask.configure {
            // Skiko loads native libs via a restricted method; future JDKs block it by default.
            jvmArgs("--enable-native-access=ALL-UNNAMED")
            // Compose's test renderer draws offscreen; headless skips AWT's display probe so the
            // tests need no X server on Linux (no Xvfb, no stale-lock hang).
            jvmArgs("-Djava.awt.headless=true")
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
        }
        // The screen tests live in commonTest, so they run on BOTH the JVM (fast loop, offscreen —
        // see the jvm block above) and iosSimulatorArm64 (`ios-test` in CI). That is the standing rule
        // — "every unit test runs on the iOS simulator too" — and it bites hardest here: iOS renders
        // these screens through a different Compose backend than the desktop one, so a JVM-only suite
        // never sees the target that ships.
        commonTest.dependencies {
            implementation(kotlin("test"))
            // The multiplatform `runComposeUiTest` API (no JUnit4 rule — that artifact is JVM-only).
            implementation(libs.compose.ui.test)
        }
        jvmTest.dependencies {
            // Skiko's desktop native binaries — the JVM renderer the offscreen scene draws into.
            implementation(compose.desktop.currentOs)
        }
    }
}

// ---- Coverage bounds (`docs/architecture.md`) ---------------------------------------------
//
// A FLOOR on this module's coverage, seeded at what the tree measured when the gate landed, and
// permitted to move in one direction only: UP. The destination is full coverage, and these numbers
// are the distance still to travel.
//
// RAISING a bound is ordinary work - do it in the change that makes it true. LOWERING one requires a
// stated forcing proof in that change's description, naming what makes the loss of coverage
// unavoidable. Nothing checks this: it is a ratchet carried by this paragraph and by review, and it
// is deliberately NOT a proof. `docs/architecture.md` carries the same contract at the opposite
// polarity - a ceiling that may only fall.
//
// TWO RULES, because they fail on different things. The aggregate catches a broad slide that leaves
// every package above the floor; the PACKAGE FLOOR - "no package here is worse than this" - catches
// one package rotting behind well-tested neighbours, which is the shape an untested class has.
//
// ENGINE: Kover's default, not JaCoCo. The two disagree by up to 26% on a single package's
// denominator, so every number below is engine-specific and switching engines means re-seeding all
// of them in that same change.
//
// Bounds are whole percentages (`minValue` is an `Int`), so each concedes up to 1% of its scope.
//
// BRANCH is depressed structurally here: the Compose compiler emits `$changed` bitmask and
// default-argument arms onto every `@Composable` declaration line, and many cannot take both
// paths under test. Read this number against this module's own history, never across the Compose
// boundary. How much of the gap is unreachable is not yet measured.
kover {
    reports {
        total {
            verify {
                onCheck = true
                rule(":ui:screens aggregate") {
                    bound {
                        minValue = 93
                        coverageUnits = CoverageUnit.INSTRUCTION
                    }
                    bound {
                        minValue = 59
                        coverageUnits = CoverageUnit.BRANCH
                    }
                }
                // No per-package BRANCH rule: branch denominators per package run as low as 6 in this
                // tree, where a single uncovered arm moves the number by 17 points.
                rule(":ui:screens package floor") {
                    groupBy = GroupingEntityType.PACKAGE
                    bound {
                        minValue = 93
                        coverageUnits = CoverageUnit.INSTRUCTION
                    }
                }
            }
        }
    }
}
