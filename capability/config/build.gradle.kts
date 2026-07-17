// MIGRATION STEP 4 emptied this module: its last source (`KeychainConfigStore`, iosMain) moved to
// `:adapter:ios:ext-safe`; the whole commonMain config surface (EventConfig, the EventLink codec,
// the ConfigRead mapping) moved to `:domain` at step 3a. The skeleton stays until the module
// deletions of steps 5/6 (only the two `:app:ios:*` satellites die at step 4).

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
    jvm()
    iosArm64()
    iosSimulatorArm64()
}
