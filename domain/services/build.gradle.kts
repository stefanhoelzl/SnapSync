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
            implementation(project(":domain:domain"))
            implementation(project(":domain:ports"))
            // How work enters: the structured log's lines, which the process's writers render onto its sinks.
            implementation(project(":domain:inbound"))
            // The backend's HTTP shapes (`api/openapi.yaml`): the gateway sends its routes and reads its replies.
            implementation(project(":domain:wire"))
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
            // A date-formatting double formats a `LocalDateTime`, the value `DateFormats` takes.
            implementation(libs.kotlinx.datetime)
        }
    }
}

// The two databases this zone owns (`docs/architecture.md`, section 9). Each from its own source dir (two
// `create` blocks may not share srcDirs). The generated packages sit under this zone's package, so the zone's
// compile boundary covers generated code too; the pinned db FILENAMES are the runtime identity, never the
// packages.
//
// ---- The schema is the migration chain ------------------------------------------------------------------------
//
// `deriveSchemaFromMigrations`: each database's schema is its `.sqm` files and nothing else — a fresh install runs the
// whole chain from the baseline (`11.sqm` for the ledger, `4.sqm` for the download store, each the oldest version a
// device holds, squashed), an upgrading device runs the files from its version on. With one source there is no second
// route to drift from it, so SQLDelight registers no `verify…Migration` task and keeps no schema snapshot.
//
// What still has to hold, and where it is held:
// - A SHIPPED MIGRATION IS NEVER EDITED: an edit splits upgraded phones from fresh installs, silently. Every `.sqm`
//   is pinned by digest in `:test:architecture`'s `MigrationPinTest`; a new one adds its pin.
// - A migration keeps its rows: the services' migration suites, each entering the database at its version through
//   the `Databases` port (`:adapter:generic:mock` commonTest).
// - Each platform's SQLite runs every statement and migrates from the oldest version: `DatabasesContract`'s schema
//   clauses (`:test:contracts`).
sqldelight {
    databases {
        create("LedgerDatabase") {
            packageName.set("app.snapsync.services.ledger.db")
            srcDirs.setFrom("src/commonMain/sqldelight/ledger")
            deriveSchemaFromMigrations.set(true)
            // The sqlite-3-25 dialect: the oldest SQLite a device runs is Android 11's 3.28 (minSdk 30), and the
            // dialect is what makes SQLDelight refuse anything newer at compile time — a 3.35 dialect let a
            // `DROP COLUMN` through that left downloads.db unopenable on Android 11–13 (SNAPSYNC-59/60). Not lower:
            // the record write's upsert-with-WHERE (Ledger.sq `recordUnlessSettled`) needs SQLite ≥ 3.24.
            dialect(libs.sqldelight.dialect.sqlite)
        }
        create("DownloadDatabase") {
            packageName.set("app.snapsync.services.downloads.db")
            srcDirs.setFrom("src/commonMain/sqldelight/download")
            deriveSchemaFromMigrations.set(true)
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
