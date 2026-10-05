plugins {
    alias(libs.plugins.kotlin.jvm)
}

// The project's own detekt rules (`docs/architecture.md`, "Localization"), loaded by every detekt task the root
// registers. detekt runs them inside its own Kotlin runtime, so they are compiled against `detekt-api` only and
// keep to the plain stdlib.
kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}

java {
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    compileOnly(libs.detekt.api)
    testImplementation(libs.detekt.api)
    testImplementation(libs.detekt.test)
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
