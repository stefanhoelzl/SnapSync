plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
}

dependencies {
    // The shared harness library: `PhoneFrame` + the `StatusPane` composition glue.
    implementation(project(":app:desktop"))
    // The stand-in seams the forge `PanelController` constructs its cells from.
    implementation(project(":domain:permission"))
    implementation(project(":domain:status"))
    implementation(project(":domain:presentation"))
    implementation(project(":capability:config"))
    implementation(project(":capability:event-creation-ui"))
    // The control panel is deliberately raw Material 3, not App* (spec: desktop-test-harness).
    implementation(compose.material3)
    implementation(compose.desktop.currentOs)
}

// Compose Desktop's run task does NOT inherit kotlin { jvmToolchain(...) }; without an
// explicit javaHome it launches on the Gradle JVM -> UnsupportedClassVersionError.
val toolchainLauncher = javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(libs.versions.jdk.get().toInt()))
}

// HiDPI: on Linux the JVM often fails to auto-detect the display scale, so the harness renders at 1x
// and the compositor bitmap-upscales it (tiny + blurry). Force the render scale so the phone frame is
// crisp at ship proportions. Defaults to 2 (4K/Retina-class); tune to your monitor without editing
// this file: `./gradlew :app:desktop:ui:run -PuiScale=1` (or 1.5, etc.).
val uiScale = (project.findProperty("uiScale") as String?) ?: "2"

compose.desktop {
    application {
        mainClass = "app.snapsync.desktop.MainKt"
        javaHome = toolchainLauncher.get().metadata.installationPath.asFile.absolutePath
        // Skiko loads native libs via a restricted method; future JDKs block it by default.
        jvmArgs += "--enable-native-access=ALL-UNNAMED"
        jvmArgs += "-Dsun.java2d.uiScale=$uiScale"
    }
}
