import kotlinx.kover.gradle.plugin.dsl.CoverageUnit
import kotlinx.kover.gradle.plugin.dsl.GroupingEntityType

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    // `UiState` is `@Serializable` so the dev/test control channel can serve the REAL reduced state
    // rather than a hand-written mirror of it (`:test:rig`). Annotations only — the encoder is
    // compiler-generated, so there is no projection that could drift from what the screen renders,
    // which is what lets the rig hold no tests. A rig-side DTO was the alternative and was rejected
    // for exactly that reason.
    alias(libs.plugins.kotlin.serialization)
    // Coverage measurement (capability `coverage-bounds`). Applied here rather than in a
    // `subprojects {}` block so the instrumented set is readable per module.
    alias(libs.plugins.kover)
}
// Coverage (capability `coverage-bounds`). `:ui:screens`' tests drive the container host
// that lives here.
//
// The report is filtered back to this module's OWN classes. The crediting edge itself is
// declared in the ROOT build file: these build scripts are read as TEXT by the zone-diagram
// generator, which would render the edge backwards.
kover {
    reports {
        filters {
            includes {
                projects.add(":ui:presentation")
            }
        }
    }
}


// The FORGE preset table (`ForgeStatusHost.kt`) is compiled in ONLY under `-Psnapsync.forge=true`.
//
// It exists to render marketing screenshots and is used by nothing else — not the desktop forge harness,
// which forges through its own control panel, and not the app. Leaving it in `commonMain` meant the preset
// table and the states it fabricates shipped in every binary, reachable in principle by anything that could
// call `forgeStatusHost`. Now the forge binary is the only thing that compiles it.
val forgeEnabled = providers.gradleProperty("snapsync.forge").map(String::toBoolean).getOrElse(false)

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
    jvm()
    iosArm64()
    iosSimulatorArm64()
    sourceSets {
        if (forgeEnabled) {
            commonMain { kotlin.srcDir("src/forge/kotlin") }
            // Gated WITH its source, not left behind. `ForgeStatusHostTest` asserts the presets drive the
            // real reduction to the frames they claim — which is the property the App Store listing rests
            // on (`ios-appstore-metadata`), so it must run wherever the presets compile.
            commonTest { kotlin.srcDir("src/forgeTest/kotlin") }
        }
        commonMain.dependencies {
            api(project(":domain:model"))
            api(project(":domain:feature"))
            // The container consumes the config seam + decoder (onOpenUrl), and ConfigSource/
            // ConfigStore appear in its constructor — so they surface in this module's API.
            // The create-event seams (CreationStatusSource/EventCreator) folded into the reduction and
            // the container's constructor — so they surface in this module's API.
            api(libs.orbit.core)
            // Capability `photo-selection-policy`: LocalDateTime appears in CutoffFormatter's signature (used
            // by the join screen in :ui:screens), so it is part of this module's API.
            api(libs.kotlinx.datetime)
            // `@Serializable` on `UiState` (see the plugin note above). `:domain` keeps its own
            // serialization dep as `implementation`, so it does not reach here transitively.
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.orbit.test)
            implementation(libs.coroutines.test)
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
// This module seeded well below its siblings because `UiState.kt` was reworked shortly before the
// gate landed (`93e8eb4f`, +1437/-538 across the module). The overlay and settings surfaces - the
// rename dialog, the leave confirmation, the diagnostic sheet and the reconfigure form - were the
// untested half of that, and covering them moved it 68 -> 77.
//
// Of what is left, ~340 instructions are `kotlinx.serialization`'s generated `$Companion`/serializer
// accessors on `UiState`'s sealed tree, which nothing here serializes and no test can reach; most of
// the rest is the default-filling in the state classes' own constructors. So the remaining honest
// debt is smaller than 1448 missed instructions suggests - grep before writing a test for one.
kover {
    reports {
        total {
            verify {
                onCheck = true
                rule(":ui:presentation aggregate") {
                    bound {
                        minValue = 77
                        coverageUnits = CoverageUnit.INSTRUCTION
                    }
                    bound {
                        minValue = 44
                        coverageUnits = CoverageUnit.BRANCH
                    }
                }
                // No per-package BRANCH rule: branch denominators per package run as low as 6 in this
                // tree, where a single uncovered arm moves the number by 17 points.
                rule(":ui:presentation package floor") {
                    groupBy = GroupingEntityType.PACKAGE
                    bound {
                        minValue = 77
                        coverageUnits = CoverageUnit.INSTRUCTION
                    }
                }
            }
        }
    }
}
