import kotlinx.kover.gradle.plugin.dsl.CoverageUnit
import kotlinx.kover.gradle.plugin.dsl.GroupingEntityType

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
    // Coverage measurement (capability `coverage-bounds`). Applied here rather than in a
    // `subprojects {}` block so the instrumented set is readable per module.
    alias(libs.plugins.kover)
}
// Coverage (capability `coverage-bounds`). `:ui:screens`' Compose tests render the real
// screens, and rendering a screen is what exercises
// these components. Without this edge the module reads 48% instead of 95%.
//
// The report is filtered back to this module's OWN classes. The crediting edge itself is
// declared in the ROOT build file: these build scripts are read as TEXT by the zone-diagram
// generator, which would render the edge backwards.
kover {
    reports {
        filters {
            includes {
                projects.add(":ui:components")
            }
        }
    }
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

// ---- Coverage bounds (capability `coverage-bounds`) ---------------------------------------------
//
// A FLOOR on this module's coverage, seeded at what the tree measured when the gate landed, and
// permitted to move in one direction only: UP. The destination is full coverage, and these numbers
// are the distance still to travel.
//
// RAISING a bound is ordinary work - do it in the change that makes it true. LOWERING one requires a
// stated forcing proof in that change's description, naming what makes the loss of coverage
// unavoidable. Nothing checks this: it is a ratchet carried by this paragraph and by review, and it
// is deliberately NOT a proof. `complexity-budgets` carries the same contract at the opposite
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
// BRANCH is depressed structurally here - see the note in `:ui:screens`; the same Compose
// compiler arms apply.
kover {
    reports {
        total {
            verify {
                onCheck = true
                rule(":ui:components aggregate") {
                    bound {
                        minValue = 94
                        coverageUnits = CoverageUnit.INSTRUCTION
                    }
                    bound {
                        minValue = 63
                        coverageUnits = CoverageUnit.BRANCH
                    }
                }
                // No per-package BRANCH rule: branch denominators per package run as low as 6 in this
                // tree, where a single uncovered arm moves the number by 17 points.
                rule(":ui:components package floor") {
                    groupBy = GroupingEntityType.PACKAGE
                    bound {
                        minValue = 94
                        coverageUnits = CoverageUnit.INSTRUCTION
                    }
                }
            }
        }
    }
}
