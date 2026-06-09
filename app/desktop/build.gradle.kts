import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
}

dependencies {
    implementation(compose.desktop.currentOs)
}

// Compose Desktop's run task does NOT inherit kotlin { jvmToolchain(...) }; without an
// explicit javaHome it launches on the Gradle JVM -> UnsupportedClassVersionError.
val toolchainLauncher = javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(libs.versions.jdk.get().toInt()))
}

compose.desktop {
    application {
        mainClass = "app.snapsync.desktop.MainKt"
        javaHome = toolchainLauncher.get().metadata.installationPath.asFile.absolutePath
        // Skiko loads native libs via a restricted method; future JDKs block it by default.
        jvmArgs += "--enable-native-access=ALL-UNNAMED"
    }
}
