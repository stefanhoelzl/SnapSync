plugins {
    alias(libs.plugins.kotlin.jvm)
}

// `:test:control` — the typed JVM client of the control channel's protocol (`docs/testing.md`,
// "One control protocol, served by two hosts"), and the home of the JVM host's tests.
//
// Support group (`docs/architecture.md`, "The module set withholds; packages organize"): it links into no
// shipped-format binary. JVM-only because every caller of the protocol is: a test drives the simulator app over
// HTTP from the build host exactly as it drives the JVM host.
//
// THE COMPILE BOUNDARY IS THE READ-MODEL RULE. The wire types stay in `:test:rig`'s `commonMain` — the device
// build needs them without a client — and the rig declares its own module dependencies `implementation()`, so
// nothing it depends on reaches here. `RigState` embeds the real `UiState` (in `model/`), so this module declares
// `:domain:model`, `:domain:presentation` and `:domain:feature` itself — each explicitly, since the core's zones
// export nothing transitively. Nothing on this compile path exports `ports/`, `flow/`, `compose/` or the host, so a
// client or test naming one fails to compile. Within `feature/`, the read-model import gate (`docs/architecture.md`,
// "The zone gates") confines this module's references to the `readmodel` packages. The
// compile boundary and that gate together are the whole rule.
kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
}

dependencies {
    // The wire types (and, for the tests, the JVM host they start).
    api(project(":test:rig"))
    // `RigState.ui` is the real `UiState` (`model/`), decoded by its compiler-generated serializer.
    api(project(":domain:model"))
    api(project(":domain:presentation"))
    api(project(":domain:feature"))
    api(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.coroutines.core)

    testImplementation(kotlin("test"))
    testImplementation(libs.coroutines.test)
}

// The JVM host's tests start the real backend for their `deno` half (`:test:edge`'s consumer contract).
val apiDir = rootProject.layout.projectDirectory.dir("api")
tasks.test {
    // JUnit 4, not the platform: the contracts module (on the runtime path through the rig) binds kotlin-test to
    // JUnit 4 in its main code, and two kotlin-test framework bindings cannot coexist.
    useJUnit()
    dependsOn(":test:edge:resolveLocalDeployment")
    inputs.dir(apiDir.dir("src")).withPropertyName("liveEdgeSources")
    inputs.dir(apiDir.dir("migrations")).withPropertyName("liveEdgeMigrations")
    inputs.dir(rootProject.layout.projectDirectory.dir("deployments")).withPropertyName("liveEdgeDeployments")
    systemProperty("snapsync.apiDir", apiDir.asFile.absolutePath)
    systemProperty("snapsync.liveEdgeStore", layout.buildDirectory.dir("live-edge").get().asFile.absolutePath)
}
