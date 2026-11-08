plugins {
    alias(libs.plugins.kotlin.jvm)
    // The JVM host's tests start the real backend (`liveEdge.consumedBy`, below).
    id("snapsync.live-edge")
}

// `:test:control` — what a test says over the control channel's typed client (`:test:client`; `docs/testing.md`,
// "One control protocol, served by two hosts"): a person's forms (`Person`), the behaviour DSL, `@Verifies` — and the
// home of the JVM host's tests.
//
// Support group (`docs/architecture.md`, "The module set: three groups"): it links into no
// shipped-format binary. JVM-only because every caller of the protocol is: a test drives the simulator app over
// HTTP from the build host exactly as it drives the JVM host.
//
// THE COMPILE BOUNDARY IS THE READ-MODEL RULE. The wire types stay in `:test:rig`'s `commonMain` — the device
// build needs them without a client — and the rig declares its own module dependencies `implementation()`, so
// nothing it depends on reaches here. `RigState` embeds the real `UiState` (in `model/`), so this module declares
// `:domain:model` and `:domain:presentation` itself — each explicitly, since the core's zones export nothing
// transitively. Nothing on this compile path exports `ports/`, `flow/`, `compose/` or the host, so a client or test
// naming one fails to compile: the compile boundary is the whole rule.
kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
}

dependencies {
    // The wire types (and, for the tests, the JVM host they start).
    api(project(":test:rig"))
    // The client (`RigClient`, `:test:client`) and the typed services it calls instead of a string verb.
    api(project(":test:client"))
    api(project(":test:levers"))
    // `@Verifies`, which every test driving the protocol carries: its own module so a Compose test can carry it too.
    api(project(":test:verifies"))
    // `RigState.ui` is the real `UiState` (`model/`), decoded by its compiler-generated serializer.
    // A person's choices name the domain's values (`Choices.direction`).
    api(project(":domain:domain"))
    api(project(":ui:state"))
    api(project(":domain:presentation"))
    api(libs.ktor.client.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.coroutines.core)

    testImplementation(kotlin("test"))
    testImplementation(libs.coroutines.test)
}

// The JVM host's tests start the real backend for their `deno` half (`:test:edge`'s consumer contract).
tasks.test {
    // JUnit 4, not the platform: the contracts module (on the runtime path through the rig) binds kotlin-test to
    // JUnit 4 in its main code, and two kotlin-test framework bindings cannot coexist.
    useJUnit()
}
liveEdge.consumedBy(tasks.test)
