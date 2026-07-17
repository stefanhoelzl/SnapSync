// MIGRATION STEP 4 emptied this module: its only source (`PhotoLibraryPermission`, iosMain) moved
// to `:adapter:ios:app-only`; the `PhotoAccess*` ports live in `:domain` since step 3a. The
// skeleton stays until the module deletions of steps 5/6 (only the two `:app:ios:*` satellites
// die at step 4).

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
    jvm()
    iosArm64()
    iosSimulatorArm64()
}
