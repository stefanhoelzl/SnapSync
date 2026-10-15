plugins {
    // A core layer (`snapsync.layer`): targets, coverage at zero, the shared test dependencies.
    id("snapsync.layer")
    alias(libs.plugins.sqldelight)
}

// The core's `data` layer (`docs/architecture.md`, "The layers"): the repositories over the ports and the backend's
// protocol over the transport — it TRANSLATES and never decides (`DataShape`): every branch is a domain verdict. The
// old zones depend on it while they move (MIGRATING, `ModuleSetTest`); it never depends on them.

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":domain:domain"))
            implementation(project(":domain:ports"))
            implementation(project(":domain:wire"))
            implementation(project(":domain:inbound"))
            // A timestamp's text: the store's date columns spell it.
            implementation(project(":domain:format"))
            // The SQLDelight runtime: the shared database below, generated from its migration chain.
            api(libs.sqldelight.runtime)
            // The read-model cells it keeps (`StateFlow`) and the lock a refresh serializes on.
            api(libs.coroutines.core)
        }
    }
}

// The shared database, `ledger.db` (`docs/architecture.md`, "Storage"): the one database both processes open
// read-write.
// Its schema is its migration chain and nothing else (`deriveSchemaFromMigrations`): a fresh install runs the whole
// chain from the baseline (`11.sqm`, the oldest version a device holds, squashed), an upgrading device runs the files
// from its version on.
// - A SHIPPED MIGRATION IS NEVER EDITED: every `.sqm` is pinned by digest in `:test:architecture`'s `MigrationPinTest`.
// - A migration keeps its rows: the store's migration suites, each entering the database at its version through the
//   `Databases` port.
// - Each platform's SQLite runs every statement and migrates from the oldest version: `DatabasesContract`'s schema
//   clauses (`:test:contracts`).
// The generated package sits under this layer's, so its compile boundary covers the generated code; the pinned file
// NAME is the runtime identity, never the package.
sqldelight {
    databases {
        create("LedgerDatabase") {
            packageName.set("app.snapsync.data.store.db")
            srcDirs.setFrom("src/commonMain/sqldelight/ledger")
            deriveSchemaFromMigrations.set(true)
            // The sqlite-3-25 dialect: the oldest SQLite a device runs is Android 11's 3.28 (minSdk 30), and the
            // dialect is what makes SQLDelight refuse anything newer at compile time. Not lower: the record write's
            // upsert-with-WHERE (Ledger.sq `recordUnlessSettled`) needs SQLite ≥ 3.24.
            dialect(libs.sqldelight.dialect.sqlite)
        }
    }
}
