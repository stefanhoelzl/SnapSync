package app.snapsync.journeys.run

import app.snapsync.journeys.device.AndroidDriver
import app.snapsync.journeys.device.ProcessShell
import java.io.File
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
            "run" -> when (val platform = flags.required("platform")) {
                "android" -> device(flags, out) { evidence ->
                    val adb = flags.optional("adb") ?: "adb"
                    AndroidRun(
                        AndroidDriver(ProcessShell, adb, flags.required("serial")),
                        apk = File(flags.optional("apk") ?: ANDROID_APK),
                        api = File(flags.optional("api") ?: "api"),
                        evidence = evidence,
                        runner = runner,
                    ).run()
                }
                else -> usage("no platform '$platform' (android)")
            }
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

/**
 * A device run, its evidence in `--out` (default `build/<platform>-journeys`, which CI keeps), its resources sampled
 * throughout: exit 0 when [run] answers that every journey passed, 1 otherwise — and 1, with why in `result`, when the
 * run stopped before them.
 */
private fun device(flags: Flags, out: PrintStream, run: (Evidence) -> Boolean): Int {
    val evidence = Evidence(File(flags.optional("out") ?: "build/${flags.required("platform")}-journeys"))
    return ResourceSampler(evidence, ProcessShell).use {
        try {
            if (run(evidence)) {
                evidence.result("journeys: every journey passed")
                EXIT_OK
            } else {
                evidence.result(
                    "journeys: FAILED — a journey failed (the report above, the evidence in ${evidence.dir})",
                )
                EXIT_FAILED
            }
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            evidence.result("journeys: FAILED — ${e.message ?: e::class.simpleName}")
            out.flush()
            EXIT_FAILED
        }
    }
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

/** The journey only Android plays: a force-stop, which no tool can perform on an iPhone. */
internal const val FORCE_STOP_JOURNEY = "app.snapsync.journeys.ForceStopJourney"

/** The rig release build `scripts/journeys-build android` leaves. */
private const val ANDROID_APK = "app/android/build/outputs/apk/release/android-release.apk"

/** The system properties the journeys read their addresses from. */
internal const val APP_A = "snapsync.journey.appA"
internal const val BACKEND = "snapsync.journey.backend"
internal const val ANDROID_SERIAL = "snapsync.journey.androidSerial"
internal const val ADB = "snapsync.journey.adb"

internal const val EXIT_OK = 0
internal const val EXIT_FAILED = 1
internal const val EXIT_USAGE = 2

private val HELP = """
    journeys <command> [--<flag> <value>…]

      run --platform android --serial <adb serial> [--adb <path>] [--apk <path>] [--api <dir>] [--out <dir>]
          The whole device run on a BOOTED emulator: the local backend, the rig release build installed and granted,
          its adapters chosen, the journeys and the force-stop journey. Built first: scripts/journeys-build android.
          Evidence in --out (build/android-journeys). Exit 0 passed, 1 failed (why in <out>/result), 2 usage.

      journeys-only --app <url> --backend <url>
          The journeys alone, against the app whose control channel answers on --app and the local backend's API base
          --backend — both already up. Exit 0 passed, 1 a journey failed, 2 usage.

""".trimIndent()
