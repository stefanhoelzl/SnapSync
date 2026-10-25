package app.snapsync.journeys

import app.snapsync.journeys.run.JourneyRunner
import app.snapsync.journeys.run.program
import org.junit.internal.TextListener
import org.junit.runner.JUnitCore
import kotlin.system.exitProcess

/** The program (`app.snapsync.journeys.run`), with JUnit to run the journeys — this source set's alone to name. */
fun main(args: Array<String>) {
    // Ktor's client logs through SLF4J, which has no provider here and says so on every call.
    System.setProperty("slf4j.internal.verbosity", "ERROR")
    exitProcess(program(args.toList(), JUnitJourneys))
}

/** JUnit 4 in this JVM, its report on stdout as `JUnitCore`'s own command line prints it. */
private object JUnitJourneys : JourneyRunner {
    override fun run(classes: List<String>, properties: Map<String, String>): Boolean {
        properties.forEach { (name, value) -> System.setProperty(name, value) }
        val junit = JUnitCore().apply { addListener(TextListener(System.out)) }
        return junit.run(*classes.map(Class<*>::forName).toTypedArray()).wasSuccessful()
    }
}
