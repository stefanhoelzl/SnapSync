import app.snapsync.buildlogic.SchemaStatementsTask

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    // The `android` target (`docs/architecture.md`, "Zones inside the core"): the Android app links this module.
    id("snapsync.android")
}

// PORT CONTRACTS (`docs/architecture.md`): the contract mechanism and every port contract, as clause
// VALUES — one explicit list per port that every binding runs, on CI through a thin `@Test` and in-app on
// a device through the rig. A list rather than `@Test` methods because Kotlin/Native has no reflection:
// an in-app runner cannot discover test methods, and `kotlin.test` cannot skip one dynamically.
//
// CONTAINED, not support (`docs/architecture.md`, "The module set withholds"): it links into the iOS app
// — and into `:adapter:ios:ext-safe`'s rig-gated source set — ONLY under `-Psnapsync.rig=true`. Its
// withholding argument: it is the only module whose MAIN source set depends on `kotlin-test`, so no
// production module's main code can assert.
//
// The statement lists `DatabasesContract`'s schema clauses run (`SchemaStatementsTask`): read from the SQLDelight
// code `:domain:services` generates for its two databases, so a query added there is run here with nothing to
// remember. A fixture per argument type; a type with none fails this task naming the statement.
val generateSchemaStatements = tasks.register<SchemaStatementsTask>("generateSchemaStatements") {
    dependsOn(":domain:services:generateSqlDelightInterface")
    generatedCode.set(project(":domain:services").layout.buildDirectory.dir("generated/sqldelight/code"))
    outputDir.set(layout.buildDirectory.dir("generated/schemaStatements/kotlin"))
    packageName.set("app.snapsync.contracts")
    fixtures.set(
        mapOf(
            "String" to "\"f\"",
            "Long" to "1L",
            "AssetId" to "AssetId(\"f\")",
            "LedgerState" to "LedgerState.entries.first()",
            "DownloadState" to "DownloadState.entries.first()",
        ),
    )
}

// `iosArm64` because the device app links it; `jvm` + `iosSimulatorArm64` because every binding's test
// source set does. NOT INSTRUMENTED for coverage (`docs/architecture.md`): this is test equipment.
kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
    jvm()
    iosArm64()
    iosSimulatorArm64()
    sourceSets {
        commonMain {
            kotlin.srcDir(generateSchemaStatements)
        }
        commonMain.dependencies {
            api(project(":domain:model"))
            api(project(":domain:ports"))
            // `DatabasesContract` brings its own schema and reads through the driver it is handed.
            implementation(libs.sqldelight.runtime)
            // The schema clauses (`SchemaClauses.kt`) run every statement through the databases' generated code.
            implementation(project(":domain:services"))
            api(kotlin("test"))
            api(libs.coroutines.test)
            implementation(libs.coroutines.core)
            // The backend contracts' setup (`Edge.kt`) enters states through the edge's public HTTP surface:
            // `HttpClient` is in `EdgeSetup`'s constructor, so it is API; the JSON is only read and built.
            api(libs.ktor.client.core)
            implementation(libs.kotlinx.serialization.json)
        }
        // kotlin-test's @Test on JVM comes from a framework artifact the Kotlin plugin attaches to TEST
        // compilations only; the bindings' JVM test tasks run JUnit 4.
        jvmMain.dependencies {
            implementation(kotlin("test-junit"))
        }
        // The same framework artifact for the Android rig build, which links this module's main code.
        androidMain.dependencies {
            implementation(kotlin("test-junit"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
