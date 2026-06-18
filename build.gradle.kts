// Plugins are declared here (apply false) so every subproject shares ONE classloader for the
// Kotlin Gradle Plugin. Without this, each subproject loads KGP on its own classloader; the
// Apple targets' global build services (e.g. SwiftPMLockTaskAggregationBuildService) then fail
// to cast across classloaders — a configuration error that only surfaces once iOS targets exist.
plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.compose) apply false
    alias(libs.plugins.sqldelight) apply false
}
