plugins {
    alias(libs.plugins.kotlin.multiplatform)
    // The allowed targets, declared once (`docs/architecture.md`, "Zones inside the core").
    id("snapsync.targets")
    alias(libs.plugins.sqldelight)
    // The zero coverage gate (`docs/architecture.md`, "Coverage"): no instruction and no branch missed.
    id("snapsync.coverage-zero")
    // The simulator test run's standard streams, beside its failure messages.
    id("snapsync.simulator-test-output")
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
            // the SQLDelight runtime (the generated databases below), kermit (the stores' own diagnostics),
            // serialization-json.
            api(libs.coroutines.core)
            implementation(libs.sqldelight.runtime)
            implementation(libs.kermit)
            // The event-album map's JSON encoding (no generated serializers: the map's builtins only).
            implementation(libs.kotlinx.serialization.json)
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
// WHAT IT DOES NOT COVER: schema only. A data-only migration changes no schema, so this task
// cannot tell a right rewrite from a wrong one — the store tests assert those.
sqldelight {
    databases {
        create("LedgerDatabase") {
            packageName.set("app.snapsync.services.ledger.db")
            srcDirs.setFrom("src/commonMain/sqldelight/ledger")
            schemaOutputDirectory.set(file("src/commonMain/sqldelight/ledger/databases"))
            // The sqlite-3-35 dialect: the default rejects ALTER TABLE … DROP COLUMN (migrations use it), and the
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

// Coverage (`docs/architecture.md`): at zero, under `snapsync.coverage-zero`. The crediting edges that let the mocks'
// suites, `:test:feature` and `:adapter:generic:app`'s tests count here are in the root build file.
//
// SQLDelight's GENERATED packages leave the report, so the gate never reads them: nobody writes or reviews them, and
// holding them at zero would test a code generator's output rather than this module. What they run is held instead by
// `DatabasesContract`'s schema clauses, every statement on each platform's own SQLite (`docs/testing.md`).
kover {
    reports {
        filters {
            excludes {
                packages("app.snapsync.services.ledger.db", "app.snapsync.services.downloads.db")
            }
        }
    }
}
