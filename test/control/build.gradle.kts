plugins {
    alias(libs.plugins.kotlin.jvm)
}

// `:test:control` — the typed JVM client of the control channel's protocol (capability `testing-architecture`,
// "One control protocol, served by two hosts"), and the home of the JVM host's tests.
//
// Support group (`module-architecture`, "The module set withholds; packages organize"): it links into no
// shipped-format binary. JVM-only because every caller of the protocol is: a test drives the simulator app over
// HTTP from the build host exactly as it drives the JVM host.
//
// THE COMPILE BOUNDARY IS THE READ-MODEL RULE. The wire types stay in `:test:rig`'s `commonMain` — the device
// build needs them without a client — and the rig declares its own module dependencies `implementation()`, so
// nothing it depends on reaches here. `RigState` embeds the real `UiState`, so this module declares
// `:ui:presentation` itself, which exports `model/` and `feature/`; nothing on this compile path exports
// `ports/`, `flow/` or `compose/`, so a client or test naming one fails to compile. That is the whole rule — no
// text gate stands behind it.
kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
}

dependencies {
    // The wire types (and, for the tests, the JVM host they start).
    api(project(":test:rig"))
    // `RigState.ui` is the real `UiState`, decoded by its compiler-generated serializer.
    api(project(":ui:presentation"))
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
