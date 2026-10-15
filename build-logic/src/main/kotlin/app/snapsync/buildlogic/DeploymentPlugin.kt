package app.snapsync.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import javax.inject.Inject

/**
 * `snapsync.deployment`: the deployment's values a module compiles in (`docs/deployment.md`). `snapsync.deployment`
 * (the Gradle property) names WHICH deployment; `scripts/resolve-deployment.py` resolves it and renders its files —
 * `build/deployment.properties` among them, which this reads — so the app, the backend, the iOS project and the site all
 * derive from one declared value rather than each holding a copy.
 *
 * The resolver runs at CONFIGURATION time, because what it yields is generated into a source set, and ONCE per build
 * however many modules apply this ([DeploymentResolution]). Its renderings are side effects other consumers read
 * (the iOS project's `Deployment.xcconfig` and `Deployment.plist`, `build/deployment.properties`), so it runs whenever
 * a module applying this is configured. It is stdlib-only Python — the one runtime present on every runner and dev
 * machine that also has Gradle.
 */
class DeploymentPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val resolution = project.gradle.sharedServices
            .registerIfAbsent("snapsyncDeployment", DeploymentResolution::class.java) {}
            .get()
        val values = resolution.resolve(project)
        project.extensions.create("deployment", DeploymentExtension::class.java, project, values)
    }
}

/** The resolved deployment, shared by every module that applies [DeploymentPlugin]: the resolver runs once. */
abstract class DeploymentResolution : BuildService<BuildServiceParameters.None> {
    private var values: Map<String, String>? = null

    @Synchronized
    fun resolve(project: Project): Map<String, String> = values ?: run {
        val name = project.providers.gradleProperty("snapsync.deployment").get()
        val repoRoot = project.rootProject.layout.projectDirectory.asFile
        project.providers.exec {
            workingDir(repoRoot)
            commandLine("python3", "scripts/resolve-deployment.py", name, "--quiet")
        }.result.get().assertNormalExitValue()
        val rendered = repoRoot.resolve("build/deployment.properties")
        if (!rendered.isFile) throw GradleException("the resolver produced no ${rendered.path} for deployment '$name'")
        rendered.readLines()
            .filterNot { it.isBlank() || it.startsWith("#") }
            .associate { line -> line.substringBefore('=') to line.substringAfter('=') }
            .also { values = it }
    }
}

/** What a module generates from the deployment. Each call registers one task and adds its output to `commonMain`. */
abstract class DeploymentExtension @Inject constructor(
    private val project: Project,
    private val values: Map<String, String>,
) {
    /**
     * `object EventWindow { val MAX: Duration }` in [packageName], from the deployment's `eventWindowMaxSeconds`: the
     * longest event range `POST /events` accepts, measured in instants. A `val`, not a `const`: the object is then
     * loaded, and covered, by the tests of the rules that read it.
     */
    fun generateEventWindow(packageName: String) {
        val seconds = values["eventWindowMaxSeconds"]?.toLongOrNull()
            ?: throw GradleException("the deployment resolved no numeric `eventWindowMaxSeconds`: the window has no bound")
        generate(
            "generateEventWindow",
            packageName,
            "EventWindow",
            """
            import kotlin.time.Duration
            import kotlin.time.Duration.Companion.seconds

            /**
             * The longest event range — and share range — the backend accepts, measured in instants: `endsAt - startsAt`
             * in seconds, as `POST /events` validates it.
             */
            object EventWindow {
                val MAX: Duration = ${seconds}L.seconds
            }
            """.trimIndent(),
        )
    }

    /**
     * `const val LINK_ORIGIN` in [packageName], from the deployment's `domain`: the event link's canonical origin, the
     * one constant both halves of the link codec are anchored to.
     */
    fun generateLinkOrigin(packageName: String) {
        val domain = values["domain"]
            ?: throw GradleException("the deployment resolved no `domain`: the event link has no origin")
        generate(
            "generateLinkOrigin",
            packageName,
            "LinkOrigin",
            """
            /**
             * The invite link's canonical origin. Both halves of the codec are anchored here: `InviteLinkText.format`
             * writes it and `InviteLinkText.parse` matches it, so producer and consumer cannot drift.
             */
            public const val LINK_ORIGIN: String = "https://$domain"
            """.trimIndent(),
        )
    }

    private fun generate(taskName: String, packageName: String, fileName: String, body: String) {
        val task = project.tasks.register(taskName, GenerateDeploymentSource::class.java) {
            relativePath.set("${packageName.replace('.', '/')}/$fileName.kt")
            source.set("// GENERATED by `snapsync.deployment` from the resolved deployment.\npackage $packageName\n\n$body\n")
            outputDir.set(project.layout.buildDirectory.dir("generated/deployment/$taskName/kotlin"))
        }
        project.pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
            project.extensions.getByType(KotlinMultiplatformExtension::class.java)
                .sourceSets.getByName("commonMain").kotlin.srcDir(task)
        }
    }
}

/** Writes one generated Kotlin file. Its whole text is the input, so a changed deployment value re-runs it. */
@CacheableTask
abstract class GenerateDeploymentSource : DefaultTask() {
    @get:Input
    abstract val relativePath: Property<String>

    @get:Input
    abstract val source: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun write() {
        val file = outputDir.get().asFile.resolve(relativePath.get())
        outputDir.get().asFile.deleteRecursively()
        file.parentFile.mkdirs()
        file.writeText(source.get())
    }
}
