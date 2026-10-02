package app.snapsync.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.tasks.TaskProvider
import org.gradle.process.JavaForkOptions

/**
 * `:test:edge`'s CONSUMER CONTRACT, written once (`test/edge/build.gradle.kts`; `docs/testing.md`): a task whose JVM
 * starts `LiveEdge` — the real `api/` as a local Deno process — gets it through `liveEdge.consumedBy(task)`.
 */
class LiveEdgePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.extensions.create("liveEdge", LiveEdgeConsumers::class.java, project)
    }
}

abstract class LiveEdgeConsumers(private val project: Project) {

    /**
     * Wires [task] to the live edge:
     *
     *  - the `local` deployment is RESOLVED first (`:test:edge:resolveLocalDeployment`): `serve.ts` imports the generated
     *    rendering, and a missing one is a module-not-found rather than a failure anyone could read;
     *  - the backend's sources are INPUTS of the task. Without them a change touching only `api/` leaves a test task
     *    up-to-date, and the suites that exist to catch exactly that change would never run against it;
     *  - `LiveEdge` is told where the backend lives (`snapsync.apiDir`) and where its filesystem store goes
     *    (`snapsync.liveEdgeStore`, under this module's build directory).
     */
    fun <T> consumedBy(task: TaskProvider<T>) where T : Task, T : JavaForkOptions {
        val root = project.rootProject.layout.projectDirectory
        val apiDir = root.dir("api")
        val store = project.layout.buildDirectory.dir("live-edge")
        task.configure {
            dependsOn(":test:edge:resolveLocalDeployment")
            inputs.dir(apiDir.dir("src")).withPropertyName("liveEdgeSources")
            inputs.dir(apiDir.dir("migrations")).withPropertyName("liveEdgeMigrations")
            inputs.dir(root.dir("deployments")).withPropertyName("liveEdgeDeployments")
            systemProperty("snapsync.apiDir", apiDir.asFile.absolutePath)
            systemProperty("snapsync.liveEdgeStore", store.get().asFile.absolutePath)
        }
    }
}
