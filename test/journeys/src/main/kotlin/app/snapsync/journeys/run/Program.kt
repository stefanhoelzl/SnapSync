package app.snapsync.journeys.run

import java.io.PrintStream

// `journeys <command> [--<flag> <value>…]`: the device runs of the `journeys (ios|android)` CI jobs, and their
// reproduction on a Mac or this box (`docs/testing.md`, "All-real journeys are the contracts' safety net").

/** What runs the journeys: JUnit, in this program's own JVM. The journeys' source set supplies it. */
fun interface JourneyRunner {
    /** Runs the test [classes] with each of [properties] set; answers whether all of them passed. */
    fun run(classes: List<String>, properties: Map<String, String>): Boolean
}

/** Runs one command line; answers the exit code. */
fun program(
    args: List<String>,
    runner: JourneyRunner,
    out: PrintStream = System.out,
    err: PrintStream = System.err,
): Int =
    try {
        val command = args.firstOrNull() ?: usage("no command")
        val flags = Flags.parse(args.drop(1))
        when (command) {
            "help", "--help", "-h" -> out.print(HELP).let { EXIT_OK }
            "journeys-only" -> {
                val passed = runner.run(
                    listOf(JOURNEYS),
                    mapOf(APP_A to flags.required("app"), BACKEND to flags.required("backend")),
                )
                if (passed) EXIT_OK else EXIT_FAILED
            }
            else -> usage("no command '$command'")
        }
    } catch (e: UsageError) {
        err.println("journeys: ${e.message} (see journeys --help)")
        EXIT_USAGE
    }

/** `--<name> <value>` pairs. */
internal class Flags private constructor(private val values: Map<String, String>) {
    fun required(name: String): String = values[name] ?: usage("--$name <value> is required")

    fun optional(name: String): String? = values[name]

    companion object {
        fun parse(args: List<String>): Flags {
            val values = mutableMapOf<String, String>()
            var i = 0
            while (i < args.size) {
                val name = args[i].takeIf { it.startsWith("--") }?.removePrefix("--") ?: usage("'${args[i]}' is no flag")
                values[name] = args.getOrNull(i + 1) ?: usage("--$name needs a value")
                i += 2
            }
            return Flags(values)
        }
    }
}

internal class UsageError(message: String) : Exception(message)

internal fun usage(message: String): Nothing = throw UsageError(message)

/** The journeys every platform runs. */
internal const val JOURNEYS = "app.snapsync.journeys.Journeys"

/** The system properties the journeys read their addresses from. */
internal const val APP_A = "snapsync.journey.appA"
internal const val BACKEND = "snapsync.journey.backend"

internal const val EXIT_OK = 0
internal const val EXIT_FAILED = 1
internal const val EXIT_USAGE = 2

private val HELP = """
    journeys <command> [--<flag> <value>…]

      journeys-only --app <url> --backend <url>
          The journeys alone, against the app whose control channel answers on --app and the local backend's API base
          --backend — both already up. Exit 0 passed, 1 a journey failed, 2 usage.

""".trimIndent()
