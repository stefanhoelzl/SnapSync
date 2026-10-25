plugins {
    alias(libs.plugins.kotlin.jvm)
}

// `:test:client` — the typed client of the control channel (`docs/testing.md`, "One control protocol, served by two
// hosts"): `RigClient`, its connection to the host's kRPC route, and the waits on the host's events. A dropped
// connection is replaced: an app the operating system suspended, or that a caller killed and started again, comes
// back with its sockets gone, and a long CI run over `adb forward` or the simulator's loopback outlives one.
//
// Support group (`docs/architecture.md`, "The module set withholds; packages organize"): it links into no
// shipped-format binary. Its own module, under `:test:control` and `:test:cli`, so a caller that only calls the
// services — `rig`, the device runs — starts on the levers and kRPC's client alone, never on the domain
// `:test:control` compiles against.
kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
}

dependencies {
    // The typed services, and the wire types they carry (`RigState`, `WorldEvent`, the screen's `UiState`).
    api(project(":test:levers"))
    api(libs.ktor.client.core)
    // The connection's client is kRPC's, which `rig` sends its descriptor-built calls over.
    api(libs.kotlinx.rpc.krpc.ktor.client)
    implementation(libs.ktor.client.cio)
    implementation(libs.kotlinx.rpc.krpc.serialization.json)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.coroutines.core)

    testImplementation(kotlin("test"))
    // The JVM host the reconnects are proven against.
    testImplementation(project(":test:rig"))
}

tasks.test {
    // JUnit 4, not the platform: the contracts module (on the runtime path through the rig) binds kotlin-test to
    // JUnit 4 in its main code, and two kotlin-test framework bindings cannot coexist.
    useJUnit()
}
