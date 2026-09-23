import kotlinx.kover.gradle.plugin.dsl.CoverageUnit
import kotlinx.kover.gradle.plugin.dsl.GroupingEntityType

// `:adapter:generic:app` (spec `module-architecture`): platform-free technology implementations of the
// `:domain` ports — the Ktor HTTP clients and the SQLDelight stores. Named for the technology,
// placed by linkage: generic code links everywhere (JVM harness, app, extension), so this module
// carries no platform source set. The `generic` prefix is the platform axis (a pure path grouping,
// no build file — same as `adapter/ios/`); the `app` leaf is SHIPPABILITY — this module links into
// the shipped app AND extension binaries (both processes, unlike `:adapter:ios:app-only`, whose
// leaf encodes PROCESS linkage). Packages keep their pre-migration names deliberately (decision
// D2 of `extract-adapter-modules`): every gate and diagram scopes by directory, and the pure-move
// diff is the review artifact; package normalization rides the feature-move steps.

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.sqldelight)
    // Coverage measurement (capability `coverage-bounds`). Applied here rather than in a
    // `subprojects {}` block so the instrumented set is readable per module.
    alias(libs.plugins.kover)
}

// Coverage (capability `coverage-bounds`). The SQLDelight-GENERATED sources are excluded: nobody
// writes or reviews them, so bounding them ratchets a code generator's output rather than this
// module's tests. Effect is small and honest either way - the module measures 76.4% with them and
// 77.5% without.
kover {
    reports {
        filters {
            excludes {
                packages("app.snapsync.engine.db", "app.snapsync.downloadstore.db")
            }
        }
    }
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
    jvm()
    iosArm64()
    iosSimulatorArm64()
    sourceSets {
        commonMain.dependencies {
            api(project(":domain:model"))
            api(project(":domain:ports"))
            api(libs.coroutines.core)
            // HttpClient appears in every Ktor adapter's public constructor — consumers construct
            // their own engine (Darwin on device, MockEngine in the world/harness), so the type is API.
            api(libs.ktor.client.core)
            implementation(libs.sqldelight.runtime)
            implementation(libs.kotlinx.serialization.json)
            // Kermit for the stores' own diagnostics (the backfill sweep's positive on-device
            // evidence — sync-ledger). :domain keeps kermit `implementation`, so it is not inherited.
            implementation(libs.kermit)
            // TimeZone appears in SystemTimeZone's override of the `TimeZoneSource` port (step 9).
            implementation(libs.kotlinx.datetime)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
        // The SQLDelight stores' contract bindings (capability `port-contracts`). The contracts live in
        // `:test:contracts`' commonMain. Per-target source sets rather than `iosTest` because each target
        // brings its own SQLDelight driver (JDBC on the JVM, native on the simulator).
        val jvmTest by getting {
            dependencies {
                implementation(project(":test:contracts"))
                implementation(libs.sqldelight.driver.sqlite)
                // The backend contracts' live bindings talk to the real `api/` over a socket (`LiveEdge`). A
                // test-only engine: production picks Darwin in the iOS shells, and nothing shipped links this.
                implementation(libs.ktor.client.cio)
            }
        }
        val iosSimulatorArm64Test by getting {
            dependencies {
                implementation(project(":test:contracts"))
                implementation(libs.sqldelight.driver.native)
            }
        }
    }
}

// Full failure messages in CI: the Kotlin/Native simulator runner otherwise prints a terse
// "AssertionError at null:-1" with no expected/actual (config carried over with the re-homed
// NativeLedgerStoreTest from `:domain:engine`).
tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest>().configureEach {
    testLogging {
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = true
    }
}

// ---- The live edge (capability `port-contracts`; the backend contracts' `Live` bindings) --------------
//
// `jvmTest` launches the REAL backend (`api/src/dev/serve.ts --ephemeral`) through `LiveEdge`, so `deno` on
// PATH is a prerequisite of `./gradlew build` (capability `testing-architecture`, "The canonical check and its
// Kotlin/Native half"). Two things here keep that honest:
//
//  - the `local` deployment is RESOLVED first: `serve.ts` imports the generated rendering, and a missing one
//    is a module-not-found rather than a clause failure anyone could read;
//  - the backend's sources are INPUTS of the test task. Without them a change touching only `api/` leaves this
//    task up-to-date, and the contracts that exist to catch exactly that change would never run against it.
val apiDir = rootProject.layout.projectDirectory.dir("api")
val resolveLocalDeployment by tasks.registering(Exec::class) {
    description = "Resolves the `local` deployment the live edge serves (deno task config:local)."
    workingDir = apiDir.asFile
    commandLine("deno", "task", "config:local")
    inputs.dir(rootProject.layout.projectDirectory.dir("deployments"))
    inputs.file(rootProject.layout.projectDirectory.file("scripts/resolve-deployment.py"))
    outputs.upToDateWhen { false }
}
tasks.named<Test>("jvmTest") {
    dependsOn(resolveLocalDeployment)
    inputs.dir(apiDir.dir("src")).withPropertyName("liveEdgeSources")
    inputs.dir(apiDir.dir("migrations")).withPropertyName("liveEdgeMigrations")
    inputs.dir(rootProject.layout.projectDirectory.dir("deployments")).withPropertyName("liveEdgeDeployments")
    systemProperty("snapsync.apiDir", apiDir.asFile.absolutePath)
    systemProperty("snapsync.liveEdgeStore", layout.buildDirectory.dir("live-edge").get().asFile.absolutePath)
}

// Both databases live here (decision D3 of `extract-adapter-modules`): one module per withheld
// technology, so the two SQLDelight schemas share it, each from its own source dir (two `create`
// blocks may not share srcDirs). Generated packages are unchanged from their pre-migration homes —
// they are not runtime identity (the pinned db *filenames* are).
//
// ---- The schema snapshots (capability `sync-ledger`) --------------------------------------------
//
// `schemaOutputDirectory` is what makes `verifyCommonMain<Db>Migration` MEAN anything. That task is
// registered either way and runs inside `./gradlew build` either way — but it verifies by applying
// every migration later than a committed `.db` snapshot's version and comparing the result with the
// schema the CREATE statements produce. With no snapshot there is nothing to apply migrations to, so
// the task executes, compares nothing, and reports success. Measured before this was set: a probe
// migration adding a column absent from Ledger.sq left the task GREEN. The check cost a build gate to
// say nothing, and the drift it was cited as proving had been in the tree for as long as it had.
//
// Setting the directory also registers `generate<SourceSet><Db>Schema`, which emits the snapshot.
//
// A SNAPSHOT'S STALENESS IS NOT A DEFECT, and this is the opposite of the usual worry about a
// generated artifact committed beside its source. An OLDER snapshot has MORE migrations applied to it
// before the comparison, so it verifies more of the chain than a newer one. Forgetting to regenerate
// after adding a migration therefore weakens nothing, which is why there is no freshness gate here
// and none is wanted — unlike `architecture/`, where staleness is a real failure and IS gated.
// Regenerate a snapshot when you want a later starting point checked, never out of hygiene.
//
// WHAT IT DOES NOT COVER: schema only. A data-only migration (8.sqm) changes no schema, so this task
// cannot tell a right rewrite from a wrong one — `SqlDelightLedgerStoreTest` asserts those.
sqldelight {
    databases {
        create("LedgerDatabase") {
            packageName.set("app.snapsync.engine.db")
            srcDirs.setFrom("src/commonMain/sqldelight/ledger")
            schemaOutputDirectory.set(file("src/commonMain/sqldelight/ledger/databases"))
            // The sqlite-3-35 dialect: the default rejects ALTER TABLE … DROP COLUMN (2.sqm needs it), and the
            // record write's upsert-with-WHERE (Ledger.sq `recordUnlessSettled`) needs SQLite ≥ 3.24.
            dialect(libs.sqldelight.dialect.sqlite)
        }
        create("DownloadDatabase") {
            packageName.set("app.snapsync.downloadstore.db")
            srcDirs.setFrom("src/commonMain/sqldelight/download")
            schemaOutputDirectory.set(file("src/commonMain/sqldelight/download/databases"))
            dialect(libs.sqldelight.dialect.sqlite)
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
// THE PACKAGE FLOOR NOW GATES. It was seeded at 0 because four production classes carried no test
// at all - `HttpAttestClient` (the client behind capability `device-attestation`, 500 instructions),
// `HttpEnrollment`, `HttpDeviceFilesSource` and `SystemTime`. All four are covered now, so the floor
// rose 0 -> 75 in one step and the rule guards every package in the module. 75 is `app.snapsync.join`,
// and what is left there is generated: the decode-only DTOs' synthetic constructors, which no test can
// reach. The next real step here is `SqlDelightLedgerStore` (87%), not the DTOs.
kover {
    reports {
        total {
            verify {
                onCheck = true
                rule(":adapter:generic:app aggregate") {
                    bound {
                        minValue = 89
                        coverageUnits = CoverageUnit.INSTRUCTION
                    }
                    bound {
                        minValue = 56
                        coverageUnits = CoverageUnit.BRANCH
                    }
                }
                // No per-package BRANCH rule: branch denominators per package run as low as 6 in this
                // tree, where a single uncovered arm moves the number by 17 points.
                rule(":adapter:generic:app package floor") {
                    groupBy = GroupingEntityType.PACKAGE
                    bound {
                        minValue = 75
                        coverageUnits = CoverageUnit.INSTRUCTION
                    }
                }
            }
        }
    }
}
