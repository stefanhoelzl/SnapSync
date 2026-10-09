package app.snapsync.buildlogic.wire

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import org.gradle.workers.WorkerExecutor
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import java.io.File
import javax.inject.Inject

/**
 * `snapsync.wire` — the app's half of the wire, generated at build time from `api/openapi.yaml` and never committed
 * (`docs/architecture.md`, "The backend boundary"). Applied by `:domain:wire`, whose `commonMain` it feeds:
 *
 * - `generateWireModels` — the DTOs, by FABRIKT (`--targets HTTP_MODELS`, kotlinx.serialization, no validation),
 *   run as its own JVM from a detached configuration. Its output is checked for `java.*` imports, which would break
 *   the iOS compile far from the cause;
 * - `generateWireRoutes` — the routes, replies and limits, by [WireRoutes] — run in a Gradle worker under PROCESS
 *   ISOLATION over the `wireGenerator` configuration: its own JVM, so swagger-parser's Jackson and Guava, and
 *   KotlinPoet, never load beside the ones the build's plugins brought — nor into the daemon's metaspace, which a
 *   full build already fills (classloader isolation, tried first, exhausted it).
 */
class WirePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val libs = project.extensions.getByType(VersionCatalogsExtension::class.java).named("libs")
        val openApi = project.rootProject.layout.projectDirectory.file("api/openapi.yaml")
        val generated = project.layout.buildDirectory.dir("generated/wire")

        val fabrikt = project.configurations.detachedConfiguration(
            project.dependencies.create(libs.findLibrary("fabrikt").get().get()),
        )
        val generator = project.configurations.create("wireGenerator") {
            isCanBeConsumed = false
            isCanBeResolved = true
        }
        project.dependencies.add(generator.name, libs.findLibrary("swagger-parser-v3").get().get())
        project.dependencies.add(generator.name, libs.findLibrary("kotlinpoet").get().get())

        val models = project.tasks.register("generateWireModels", JavaExec::class.java) {
            description = "Generates the wire's DTOs from api/openapi.yaml with Fabrikt."
            classpath = fabrikt
            mainClass.set("io.fabrikt.cli.CodeGen")
            val out = generated.get().dir("models").asFile
            inputs.file(openApi).withPathSensitivity(PathSensitivity.RELATIVE)
            outputs.dir(out)
            args(
                "--api-file", openApi.asFile.absolutePath,
                "--base-package", "app.snapsync.wire",
                "--output-directory", out.absolutePath,
                "--targets", "HTTP_MODELS",
                "--serialization-library", "KOTLINX_SERIALIZATION",
                "--instant-library", "KOTLIN_TIME_INSTANT",
                "--validation-library", "NO_VALIDATION",
                // Time stays text until the app's `Timestamp` type (`openapi.yaml`, `Timestamp`); a uri is text.
                "--custom-type-mapping", "string:date-time=kotlin.String",
                "--custom-type-mapping", "string:uri=kotlin.String",
            )
            doFirst { out.deleteRecursively() }
            doLast {
                refuseJvmImports(out)
                containExperimentalMarker(out)
            }
        }
        val routes = project.tasks.register("generateWireRoutes", WireRoutesTask::class.java) {
            description = "Generates the wire's routes, replies and limits from api/openapi.yaml."
            this.openApi.set(openApi)
            generatorClasspath.from(generator)
            outputDir.set(generated.map { it.dir("routes") })
        }
        // AGP's art-profile step reads beside every source root (`<root>/../baselineProfiles`), Fabrikt's included.
        project.tasks.matching { it.name.endsWith("ArtProfile") }.configureEach { dependsOn(models) }
        project.pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
            project.extensions.configure(KotlinMultiplatformExtension::class.java) {
                sourceSets.getByName("commonMain").kotlin.srcDir(
                    models.map { generated.get().dir("models/src/main/kotlin") },
                )
                sourceSets.getByName("commonMain").kotlin.srcDir(routes.map { it.outputDir })
            }
        }
    }

    /**
     * Fabrikt marks a discriminated union `@ExperimentalSerializationApi` because it uses `@JsonClassDiscriminator`.
     * Left as is, the marker PROPAGATES: every consumer naming the type would need an opt-in. The experimental API is
     * the annotation alone, so the union opts in to it itself and its users see an ordinary type.
     */
    private fun containExperimentalMarker(dir: File) {
        dir.walkTopDown().filter { it.extension == "kt" }.forEach { f ->
            val text = f.readText()
            val contained = text.replace(
                Regex("""(?m)^@ExperimentalSerializationApi$"""),
                "@OptIn(ExperimentalSerializationApi::class)",
            )
            if (contained != text) f.writeText(contained)
        }
    }

    /** Generated common code must not reach for the JVM: it would fail the iOS compile, far from the cause. */
    private fun refuseJvmImports(dir: File) {
        val offenders = dir.walkTopDown().filter { it.extension == "kt" }
            .filter { f -> f.readLines().any { it.startsWith("import java.") || it.startsWith("import javax.") } }
            .toList()
        if (offenders.isNotEmpty()) {
            throw GradleException("Fabrikt generated JVM imports into common code: ${offenders.map { it.name }}")
        }
    }
}

/** Runs [WireRoutes] over the description in a worker JVM of its own. */
abstract class WireRoutesTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val openApi: RegularFileProperty

    @get:Classpath
    abstract val generatorClasspath: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val workers: WorkerExecutor

    @TaskAction
    fun generate() {
        workers.processIsolation { classpath.from(generatorClasspath) }.submit(WireRoutesAction::class.java) {
            openApi.set(this@WireRoutesTask.openApi)
            outputDir.set(this@WireRoutesTask.outputDir)
        }
    }
}

interface WireRoutesParameters : WorkParameters {
    val openApi: RegularFileProperty
    val outputDir: DirectoryProperty
}

abstract class WireRoutesAction : WorkAction<WireRoutesParameters> {
    override fun execute() {
        val out = parameters.outputDir.get().asFile
        out.deleteRecursively()
        val dir = out.resolve(WireRoutes.PACKAGE.replace('.', '/')).apply { mkdirs() }
        val files = try {
            WireRoutes.generate(WireDescription.read(parameters.openApi.get().asFile.readText()))
        } catch (e: WireDescriptionException) {
            throw GradleException(e.message ?: "the description could not be read", e)
        }
        files.forEach { (name, content) -> dir.resolve(name).writeText(content) }
    }
}
