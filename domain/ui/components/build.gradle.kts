plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
    jvm()
    iosArm64()
    iosSimulatorArm64()
    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            // The ONLY module allowed to depend on Material 3 (design.md §5).
            implementation(compose.material3)
            // Material icon glyphs (e.g. the leave action's Logout). Contained here like Material 3 —
            // the `Icons.*` import never leaves this module; no `App*` signature carries a glyph type.
            implementation(compose.materialIconsExtended)
            // QR rendering for AppQrCode — Compose-MP-native, contained to this module like Material 3
            // (the qrose import never leaves this module; no `App*` signature carries a QR type).
            implementation(libs.qrose)
            // Plain multiplatform date-time value for AppDateTimeField's semantic signature
            // (LocalDateTime is a data/meaning type, not a Material 3 type — the containment rule is intact).
            implementation(libs.kotlinx.datetime)
        }
    }
}
