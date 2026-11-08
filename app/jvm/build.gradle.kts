// `:app:jvm` (`docs/architecture.md`, `docs/testing.md` "The JVM root"): the JVM composition root — what `:app:ios`
// is to the phone, for the JVM test equipment. It composes the app exactly as the iOS root does — `startProcess`,
// then `composeApp` — over the adapters its CALLER chooses (the mocks, real JVM storage, the real `api/`), and
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
    api(project(":core:composition:app"))
    api(project(":core:composition:process"))
    api(project(":core:composition:extension"))
    api(project(":core:presentation"))
    api(project(":adapter:generic:mock"))
    implementation(project(":ui:state"))
    implementation(project(":core:ports"))
    implementation(project(":adapter:generic:app"))
    implementation(libs.ktor.client.core)
    implementation(libs.coroutines.core)
    implementation(libs.kotlinx.datetime)
    // The status host is an Orbit container: a caller naming it needs its supertype.
    api(libs.orbit.core)
}

// Coverage (`docs/architecture.md`, "Coverage"): the JVM root is wiring, smoke-tested by the integration surface.
coverage {
    unit(alsoCoveredBy = listOf(":test:integration"))
    integration(instructions = 100, branches = 100)
}
