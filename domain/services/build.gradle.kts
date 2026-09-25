import kotlinx.kover.gradle.plugin.dsl.CoverageUnit
import kotlinx.kover.gradle.plugin.dsl.GroupingEntityType
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    // The allowed targets, declared once (`docs/architecture.md`, "Zones inside the core").
    id("snapsync.targets")
    alias(libs.plugins.sqldelight)
    // Coverage measurement (`docs/architecture.md`). Applied here rather than in a
    // `subprojects {}` block so the instrumented set is readable per module.
    alias(libs.plugins.kover)
}

// Full failure messages in CI: the Kotlin/Native simulator runner otherwise prints a terse
// "AssertionError at null:-1" with no expected/actual.
tasks.withType<KotlinNativeSimulatorTest>().configureEach {
    testLogging {
        exceptionFormat = TestExceptionFormat.FULL
        showStandardStreams = true
    }
}

// The core's `services` zone (`docs/architecture.md`, "Zones inside the core"): the shared capabilities
// built over the thin ports — what a store holds, when it is opened, what a failure means. Between `ports`
// and `feature`: it sees the vocabulary and the ports, never a feature.
//
// Zone edges are declared with `implementation()`, never `api()`: a zone must not leak to a downstream
// consumer transitively. A consumer that needs another zone declares it.
// NO iosMain source directory, ever — the targets exist so iosMain elsewhere can compile against this.
//
// Its tests that need a real SQLite database live beside the `Databases` adapters (`:adapter:generic:app`
// jvmTest, `:adapter:ios:ext-safe` iosTest), because a `:domain:*` build file names no module; the crediting
// edge that counts them here is in the root build file.

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":domain:model"))
            implementation(project(":domain:ports"))
            // The per-zone library allowlist (`docs/architecture.md`): coroutines (the stores' `Flow` shapes),
            // the SQLDelight runtime (the generated databases below), kermit (the stores' own diagnostics).
            api(libs.coroutines.core)
            implementation(libs.sqldelight.runtime)
            implementation(libs.kermit)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
        }
    }
}

// The two databases this zone owns (`docs/architecture.md`, section 9). Each from its own source dir (two
// `create` blocks may not share srcDirs). The generated packages sit under this zone's package, so the zone's
// compile boundary covers generated code too; the pinned db FILENAMES are the runtime identity, never the
// packages.
//
// ---- The schema snapshots (capability `photo-sharing`) --------------------------------------------
//
// `schemaOutputDirectory` is what makes `verifyCommonMain<Db>Migration` MEAN anything. That task is
// registered either way and runs inside `./gradlew build` either way — but it verifies by applying
// every migration later than a committed `.db` snapshot's version and comparing the result with the
// schema the CREATE statements produce. With no snapshot there is nothing to apply migrations to, so
// the task executes, compares nothing, and reports success. Measured before this was set: a probe
// migration adding a column absent from Ledger.sq left the task GREEN.
//
// Setting the directory also registers `generate<SourceSet><Db>Schema`, which emits the snapshot.
//
// A SNAPSHOT'S STALENESS IS NOT A DEFECT. An OLDER snapshot has MORE migrations applied to it before the
// comparison, so it verifies more of the chain than a newer one. Regenerate a snapshot when you want a later
// starting point checked, never out of hygiene.
//
// WHAT IT DOES NOT COVER: schema only. A data-only migration (ledger 8.sqm) changes no schema, so this task
// cannot tell a right rewrite from a wrong one — the store tests assert those.
sqldelight {
    databases {
        create("LedgerDatabase") {
            packageName.set("app.snapsync.services.ledger.db")
            srcDirs.setFrom("src/commonMain/sqldelight/ledger")
            schemaOutputDirectory.set(file("src/commonMain/sqldelight/ledger/databases"))
            // The sqlite-3-35 dialect: the default rejects ALTER TABLE … DROP COLUMN (2.sqm needs it), and the
            // record write's upsert-with-WHERE (Ledger.sq `recordUnlessSettled`) needs SQLite ≥ 3.24.
            dialect(libs.sqldelight.dialect.sqlite)
        }
        create("DownloadDatabase") {
            packageName.set("app.snapsync.services.downloads.db")
            srcDirs.setFrom("src/commonMain/sqldelight/download")
            schemaOutputDirectory.set(file("src/commonMain/sqldelight/download/databases"))
            dialect(libs.sqldelight.dialect.sqlite)
        }
    }
}

// Coverage (`docs/architecture.md`). The report is filtered to this module's OWN classes, and the
// SQLDelight-GENERATED sources are excluded: nobody writes or reviews them, so bounding them ratchets a code
// generator's output rather than this module's tests.
kover {
    reports {
        filters {
            includes {
                projects.add(":domain:services")
            }
            excludes {
                packages("app.snapsync.services.ledger.db", "app.snapsync.services.downloads.db")
            }
        }
    }
}

// Coverage bounds (`docs/architecture.md`). Each number below is a FLOOR that may only RISE: lowering one
// is a regression and needs a stated forcing proof in the PR. Seeded from MEASUREMENT on the commit that
// created this zone (98.6% instructions, 86.9% branches; the thinnest package, `databases`, 93.1%).
// ENGINE: Kover's default. Most of what counts here runs beside the adapters and in the world, through the
// crediting edges in the root build file.
kover {
    reports {
        total {
            verify {
                onCheck = true
                rule(":domain:services aggregate") {
                    bound {
                        minValue = 98
                        coverageUnits = CoverageUnit.INSTRUCTION
                    }
                    bound {
                        minValue = 86
                        coverageUnits = CoverageUnit.BRANCH
                    }
                }
                rule(":domain:services package floor") {
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
