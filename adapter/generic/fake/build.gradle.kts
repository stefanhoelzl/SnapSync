// `:adapter:generic:fake` (spec `module-architecture`): HONEST in-memory implementations of the `:domain`
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
    // Coverage measurement (capability `coverage-bounds`). Applied here rather than in a
    // `subprojects {}` block so the instrumented set is readable per module.
    alias(libs.plugins.kover)
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
    jvm()
    iosSimulatorArm64()
    sourceSets {
        commonMain.dependencies {
            api(project(":domain"))
            api(libs.coroutines.core)
        }
        // The stay-behind tests that drive `:domain` subjects through these fakes (re-homed from the
        // deleted `:domain:gallery` / `:domain:download-store` / `:capability:attest` modules at
        // migration step 10; testing rule 1 — commonTest runs on JVM and the iOS simulator).
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
        }
    }
}
