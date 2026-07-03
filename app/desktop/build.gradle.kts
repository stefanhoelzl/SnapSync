plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
}

// This is the shared Compose *library* for the desktop harnesses: it holds `PhoneFrame` and the
// `StatusPane` composition glue (construct `StatusContainerHost` from the injected seams → render the
// real `StatusScreen` inside the frame). It declares NO `compose.desktop.application` block, so it has
// no `run` task — `:app:desktop:run` is left free for the full-stack world harness. Today's forge
// harness is the child `:app:desktop:ui` (run task `:app:desktop:ui:run`), which depends on this module.
dependencies {
    implementation(project(":domain:permission"))
    implementation(project(":domain:status"))
    implementation(project(":domain:presentation"))
    implementation(project(":domain:ui"))
    implementation(project(":capability:config"))
    // `StatusPane` names the create-event seams (`CreationStatusSource`/`EventCreator`) in its
    // signature, so the edge is explicit here rather than transitive.
    implementation(project(":capability:event-creation-ui"))
    implementation(compose.runtime)
    implementation(compose.foundation)
}
