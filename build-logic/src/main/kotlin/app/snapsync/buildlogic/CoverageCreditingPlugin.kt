package app.snapsync.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * `snapsync.coverage-crediting`, applied once, by the root (`docs/architecture.md`, "Coverage"): which module's tests
 * count toward which module's coverage. A Kover crediting edge is not a compile edge — it merges coverage DATA and
 * never puts classes on a classpath — so it is declared here rather than in the consumer's build file:
 *
 *  - `:domain` modules may not name another module in their own build files (`ModuleSetTest` asserts the absence as
 *    text, because that absence is the precondition for the platform-free compile error), and
 *  - `Zones.kt` reads every build script under `adapter/`, `domain/` and `ui/` as text, so a locally declared edge
 *    would render in `architecture/zones.md` pointing the wrong way.
 *
 * Without the edges the zones measure a fraction of their coverage: the tests that need a port's mock live in
 * `:test:feature`, `:test:data` and `:adapter:generic:mock`, modules a `:domain:*` build file may not name.
 *
 * ⚠️ Name the LEAF modules, never a container: `include(":domain:model")` makes Gradle create an empty `:domain`
 * project, which applies no plugin and holds no class, so an edge from it is never wired and a report filtered to it
 * measures NOTHING — `:domain:model:koverVerify` once passed at 100% over an empty report that way.
 */
class CoverageCreditingPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val root = project
        check(root == root.rootProject) { "snapsync.coverage-crediting is the root's: it wires every subproject" }
        EDGES.forEach { (consumer, producer) ->
            root.project(consumer).plugins.withId("org.jetbrains.kotlinx.kover") {
                root.project(consumer).dependencies.add("kover", root.project(producer))
            }
        }
    }

    private companion object {
        /** Consumer → the module whose tests it is credited with. */
        val EDGES: List<Pair<String, String>> = listOf(
            ":domain:model" to ":adapter:generic:mock",
            ":domain:ports" to ":adapter:generic:mock",
            // The services' suites run beside the mocks; beside the JVM adapters runs the encrypted file format's
            // cipher, held to Tink's reference implementation there (a `:domain:*` build file names no module).
            ":domain:services" to ":adapter:generic:app",
            ":domain:services" to ":adapter:generic:mock",
            ":domain:feature" to ":adapter:generic:mock",
            // `:test:feature` holds the feature tests that compose real services over the ports' mocks
            // (`docs/testing.md`): they exercise the feature zone first, and the services, the model and the ports it
            // reaches through them.
            ":domain:feature" to ":test:feature",
            ":domain:services" to ":test:feature",
            ":domain:model" to ":test:feature",
            ":domain:ports" to ":test:feature",
            // The WIRING modules, credited by the integration surface and by nothing else it runs
            // (`docs/architecture.md`, "Coverage"): "One shared composition" says the wiring graph is not unit-tested
            // and IS smoke-tested by the integration surface, so for these the integration tests are the tests written
            // for the code. Every other module stays credited by unit tests only — integration still runs model,
            // feature, services, … and counts for none of them.
            ":domain:compose" to ":test:integration",
            ":domain:host" to ":test:integration",
            // The entry points order each trigger's steps and decide nothing (`docs/patterns.md`, "Entry points"): the
            // integration surface delivers every trigger through the operating system's side, which is what they are
            // for.
            ":domain:entrypoints" to ":test:integration",
            // The process's composition registers its metric reports' entry point; the mocks' suite drives it.
            ":domain:entrypoints" to ":adapter:generic:mock",
            // The data layer's suites run over the ports' mocks in `:test:data` (`docs/testing.md`), and so does the
            // application layer's use case, over the data it calls.
            ":domain:data" to ":test:data",
            ":domain:application" to ":test:data",
            ":app:jvm" to ":test:integration",
            ":ui:components" to ":ui:screens",
            ":domain:presentation" to ":ui:screens",
        )
    }
}
