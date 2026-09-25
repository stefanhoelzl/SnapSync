plugins {
    alias(libs.plugins.kotlin.jvm)
}

// `:test:edge` — the REAL backend, served as a local process for JVM tests (`LiveEdge`: the Deno `api/`
// run as `src/dev/serve.ts --ephemeral`, loopback-only, over a filesystem store). Support group
// (`docs/architecture.md`, "The module set withholds; packages organize"): it never links into a shipped-format
// binary, and it exists because two unrelated consumers need the same process — the backend contracts' live
// bindings (`:adapter:generic:app`'s `jvmTest`) and the world's real-backend option (`:test:world`'s
// `jvmMain`). Neither can host it for the other: in the world it would make the adapter's contract test
// depend on the module a later change takes apart, and `:test:contracts` is contained and links into rig
// builds, so it must not grow process spawning.
//
// JVM-only because a Kotlin/Native test executable under `simctl` cannot launch a process. No tests of its
// own: every consumer's run is its test, and a start failure names Deno rather than failing a clause.
//
// CONSUMER CONTRACT. `LiveEdge` reads two system properties — `snapsync.apiDir` (required) and
// `snapsync.liveEdgeStore` — and a consumer's test task must depend on [resolveLocalDeployment] and declare
// the backend's sources as inputs, or a change touching only `api/` leaves that task up-to-date and the
// suites that exist to catch it never run against it.
kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
}

dependencies {
    // `EdgeSetup` (the public-HTTP state entry) and the contract result types `LiveEdge.enter` builds.
    api(project(":test:contracts"))
    // The production interceptor `LiveEdge.client` installs, so the client under contract is the shipped one.
    implementation(project(":adapter:generic:app"))
    api(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.coroutines.core)
}

// `serve.ts` imports the generated `local` deployment rendering; a missing one is a module-not-found rather
// than a failure anyone could read. Resolved here, once, for every consumer.
val apiDir = rootProject.layout.projectDirectory.dir("api")
tasks.register<Exec>("resolveLocalDeployment") {
    description = "Resolves the `local` deployment the live edge serves (deno task config:local)."
    workingDir = apiDir.asFile
    commandLine("deno", "task", "config:local")
    inputs.dir(rootProject.layout.projectDirectory.dir("deployments"))
    inputs.file(rootProject.layout.projectDirectory.file("scripts/resolve-deployment.py"))
    outputs.upToDateWhen { false }
}
