// `:adapter:generic:fake` (`docs/architecture.md`): HONEST in-memory implementations of the `:domain`
// ports — what the world harness, the composition smoke, and the integration tests stand on. An
// adapter named for its technology ("fake", i.e. in-memory — platform-free, hence the `generic`
// platform-axis prefix), placed by linkage: it links only into test equipment, never a shipped
// binary — which is what the `fake` SHIPPABILITY leaf records (vs sibling `:adapter:generic:app`,
// which ships in both processes). Honesty is mechanical, not an adjective: every public
// type exposes its port contract plus a constructor taking initial state, and NOTHING else — the
// `FakeHonestyTest` gate in `:test:architecture` enforces it. Operator rigging (failure levers,
// inspection lists, settable cells) lives in `:test:world` wrappers around these fakes, physically
// unable to creep in here (migration step 10; decision record: `establish-target-architecture`).
//
// Targets mirror `:test:world` (jvm + iosSimulatorArm64): fakes never link into a device
// framework, so there is no `iosArm64` to pay for.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    // Coverage measurement (`docs/architecture.md`). Applied here rather than in a
    // `subprojects {}` block so the instrumented set is readable per module.
    alias(libs.plugins.kover)
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
    jvm()
    iosSimulatorArm64()
    sourceSets {
        commonMain.dependencies {
            api(project(":domain:model"))
            api(project(":domain:ports"))
            api(project(":domain:feature"))
            api(project(":domain:flow"))
            api(libs.coroutines.core)
            // The Clock double answers a zone (`TimeFactories.kt`).
            implementation(libs.kotlinx.datetime)
        }
        // The stay-behind tests that drive `:domain` subjects through these fakes (re-homed from the
        // deleted `:domain:gallery` / `:domain:download-store` / `:capability:attest` modules at
        // migration step 10; testing rule 1 — commonTest runs on JVM and the iOS simulator).
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
            // The fakes' own contract bindings (`docs/architecture.md`): only this module's test
            // source set can construct an `internal` fake in a chosen state.
            implementation(project(":test:contracts"))
            // The photo-library contracts bind the grant-aware composition production calls, over the fake
            // just as over the platform read (`docs/architecture.md`).
            implementation(project(":domain:compose"))
            // The storage services' fake-driven tests: the services over the storage mocks (`docs/testing.md`).
            implementation(project(":domain:services"))
            // The process services hand the root a Kermit writer to install (`ProcessServices.logWriters`).
            implementation(libs.kermit)
        }
    }
}
