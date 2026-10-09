package app.snapsync.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider
import org.gradle.process.JavaForkOptions

/**
 * `snapsync.transfer-fixture` — the loopback transfer fixture ([TransferFixtureService]) for a module's test task:
 * `transferFixture.consumedBy(task)` starts it before the task runs, and hands a JVM task its address as the
 * `snapsync.transferFixture` system property. A simulator test executable shares the host's loopback, so it reaches
 * the same address without being told ([TransferFixtureService.LOOPBACK_ADDRESS]).
 */
class TransferFixturePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.extensions.create("transferFixture", TransferFixtureConsumers::class.java, project)
    }
}

abstract class TransferFixtureConsumers(private val project: Project) {

    /** Starts the fixture before [task] runs; a JVM task is told where it answers. */
    fun consumedBy(task: TaskProvider<out Task>) = task.configure { serve(this) }

    /**
     * [consumedBy], for a task registered only on some hosts — the simulator tests exist on a Mac alone — named
     * rather than looked up.
     */
    fun consumedBy(taskName: String) = project.tasks.matching { it.name == taskName }.configureEach { serve(this) }

    private fun serve(task: Task) {
        val fixture = TransferFixtureService.registered(project)
        task.usesService(fixture)
        task.doFirst { fixture.get().ensureStarted() }
        (task as? JavaForkOptions)?.systemProperty("snapsync.transferFixture", TransferFixtureService.LOOPBACK_ADDRESS)
    }
}

/** The one fixture service of the build, registered on first use. */
internal fun TransferFixtureService.Companion.registered(project: Project): Provider<TransferFixtureService> =
    project.gradle.sharedServices.registerIfAbsent(NAME, TransferFixtureService::class.java) {
        parameters.script.set(project.rootProject.layout.projectDirectory.file("scripts/transfer-fixture.py"))
        parameters.log.set(project.rootProject.layout.buildDirectory.file("transfer-fixture.log"))
        parameters.port.set(PORT)
    }
