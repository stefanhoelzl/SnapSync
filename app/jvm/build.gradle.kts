// `:app:jvm` (`docs/architecture.md`, `docs/testing.md` "The JVM root"): the JVM composition root — what `:app:ios`
// is to the phone, for the JVM test equipment. It composes the app exactly as the iOS root does — `snapSyncProcess`,
// then `snapSyncHost` — over the adapters its CALLER chooses (the mocks, real JVM storage, the real `api/`), and
// models process death as `relaunch()`: the per-process faces are rebuilt over the same durable state.
//
// Wiring only, and gated as a shell (`detektAppShell`): no levers, no test DSL, no decision. The levers are the
// mocks' operator faces; the callers are the control channel's JVM host and the desktop harness.
//
// A support module: never linked into a shipped binary (`ModuleSetTest`'s SUPPORT group).
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
}

dependencies {
    // What a caller names: the composed app and its status host, and the mocks it chooses from.
    api(project(":domain:host"))
    api(project(":domain:compose"))
    api(project(":domain:presentation"))
    api(project(":adapter:generic:mock"))
    implementation(project(":domain:model"))
    implementation(project(":domain:ports"))
    implementation(project(":domain:services"))
    implementation(project(":domain:feature"))
    implementation(project(":adapter:generic:app"))
    implementation(libs.ktor.client.core)
    implementation(libs.coroutines.core)
    implementation(libs.kermit)
    implementation(libs.kotlinx.datetime)
    // The status host is an Orbit container: a caller naming it needs its supertype.
    api(libs.orbit.core)
}
