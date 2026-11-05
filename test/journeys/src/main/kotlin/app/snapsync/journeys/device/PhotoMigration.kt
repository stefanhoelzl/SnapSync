package app.snapsync.journeys.device

import java.io.File
import java.time.Instant
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * What `assetsd` has said about a fresh simulator's photo-library migrations, fed one log line at a time — the gate
 * the photo-library contracts must not start before.
 *
 * Why (measured, runs 36868431564 and 37004211450): a new simulator's system library is the runtime's template, an old
 * schema, and `assetsd` upgrades it in two parts. The foreground part runs as the library opens; the app's first write
 * waits for it. The background part is a system task `dasd` launches when it chooses — before or after that first write
 * — and while its longest action runs (`PopulateExtendedAttributes`, 96–184 s at utility QoS on a booting runner) every
 * write to the library waits. A clause that writes then blocks past its `runTest` bound and fails, wherever it falls.
 *
 * The two events, both logged by `assetsd`, keyed by `com.apple.assetsd.migration.<st_dev>.<st_ino>` of the library:
 *
 *     Submitting task request for background migration with identifier com.apple.assetsd.migration.16777230.3098355
 *     Marking task <BGNonRepeatingSystemTask: com.apple.assetsd.migration.16777230.3098355> complete
 *
 * The Syndication library is CREATED fresh and submits a task too; it is logged, never awaited (nothing measured shows
 * it holding the system library's writes). Which id is the system library's is read from the one library whose schema
 * was upgraded: its migration logs `stat: st_dev=… st_ino=…`. Read AFTER the first write has returned: by then the
 * library has opened, so every task it will submit has been submitted, and none submitted means none pending.
 *
 * A device CI restored already settled upgrades no library — its system library is current — yet may still submit a
 * task; with no upgraded library to name the system one, every submitted task is awaited.
 *
 * Fed THIS boot's lines only ([pastLogArguments]): a settled device's stored log still holds its settle boot's, and on
 * a loaded runner it can hold that boot's stat line and submission WITHOUT the completion its settle saw live (run
 * 40903027270: the settle's stream saw `… complete` at 15:52:36, the stored log ends at 15:52:10). Read back, they
 * name a pending migration this boot never runs, and every run within the hour waited out its bound and failed (that
 * run, and 40907393492 attempt 1 on the cache it saved).
 */
class PhotoMigrations {
    /** The ids of libraries whose schema was upgraded, in order — the system library's, alone. */
    private val upgraded = mutableListOf<String>()
    private val submitted = mutableListOf<String>()
    private val completed = mutableSetOf<String>()

    fun feed(line: String) {
        STAT.find(line)?.let { m ->
            val id = "$PREFIX${m.groupValues[1]}.${m.groupValues[2]}"
            if (id !in upgraded) upgraded += id
            return
        }
        SUBMIT.find(line)?.let { m ->
            if (m.groupValues[1] !in submitted) submitted += m.groupValues[1]
            return
        }
        COMPLETE.find(line)?.let { completed += it.groupValues[1] }
    }

    fun verdict(): Verdict {
        if (submitted.isEmpty()) return Verdict(State.SETTLED, "no background migration was submitted")
        if (upgraded.isEmpty()) {
            // No library's schema was upgraded on this boot — a device CI restored already settled — yet a migration was
            // submitted, and which library it is cannot be told. Awaited, every one: a wait is bounded, while a write
            // during the system library's migration fails a clause.
            val pending = submitted.filter { it !in completed }
            return if (pending.isEmpty()) {
                Verdict(State.SETTLED, "no library was upgraded and every submitted migration completed: $submitted")
            } else {
                Verdict(State.WAITING, "no library was upgraded; awaiting every submitted migration: $pending")
            }
        }
        if (upgraded.size != 1) {
            return Verdict(
                State.UNKNOWN,
                "cannot tell the system library's migration apart: upgraded libraries $upgraded, submitted $submitted",
            )
        }
        val system = upgraded.single()
        return when (system) {
            !in submitted -> Verdict(State.SETTLED, "the system library ($system) submitted no background migration")
            in completed -> Verdict(State.SETTLED, "the system library's background migration $system completed")
            else -> Verdict(State.WAITING, "the system library's background migration $system is pending")
        }
    }

    enum class State { SETTLED, WAITING, UNKNOWN }

    data class Verdict(val state: State, val why: String)

    companion object {
        /** `log`'s predicate: `assetsd`'s migration lines alone, so the log daemon's cost stays negligible. */
        const val PREDICATE = "process == \"assetsd\" AND (eventMessage CONTAINS \"com.apple.assetsd.migration.\" " +
            "OR eventMessage CONTAINS \"stat: st_dev=\")"
        private const val PREFIX = "com.apple.assetsd.migration."
        private val STAT = Regex("""stat: st_dev=(\d+) st_ino=(\d+)""")
        private val SUBMIT = Regex(
            """Submitting task request for background migration with identifier (com\.apple\.assetsd\.migration\.[\d.]+\d)""",
        )
        private val COMPLETE = Regex(
            """Marking task <BGNonRepeatingSystemTask: (com\.apple\.assetsd\.migration\.[\d.]+\d)> complete""",
        )
    }
}

/**
 * Waits for simulator [udid]'s photo library to settle its background migration, on `assetsd`'s own events: it follows
 * `log stream` (attached first) and reads `log show` (the past since [bootedAt], a host time taken just before this
 * boot's `simctl boot`), so no event falls between the two and nothing is polled. Every line read goes to [log]. FAILS
 * rather than guesses — two upgraded libraries it cannot tell apart, a stream that ends, or no completion within
 * [bound] (a broken platform, not a slow one: the longest measured migration took 184 s).
 */
fun awaitPhotoMigration(
    shell: Shell,
    udid: String,
    log: File,
    bound: Duration,
    bootedAt: Instant,
): PhotoMigrations.Verdict {
    val started = TimeSource.Monotonic.markNow()
    val logCommand = listOf("xcrun", "simctl", "spawn", udid, "log")
    val stream = ProcessBuilder(
        logCommand + listOf("stream", "--style", "compact", "--predicate", PhotoMigrations.PREDICATE),
    )
        .redirectErrorStream(true).start()
    val lines = LinkedBlockingQueue<Line>()
    thread(isDaemon = true, name = "assetsd log") {
        stream.inputStream.bufferedReader().useLines { all -> all.forEach { lines.put(Line.Read(it)) } }
        lines.put(Line.Ended)
    }
    val state = PhotoMigrations()
    fun next(): Line? = lines.poll(
        (bound - started.elapsedNow()).inWholeMilliseconds.coerceAtLeast(0),
        TimeUnit.MILLISECONDS,
    )
    fun take(line: String) {
        log.appendText("$line\n")
        state.feed(line)
    }
    return try {
        // `log stream` prints its "Filtering the log data using …" header once it is attached; only then is the past
        // read, so no event can fall between the two.
        when (val header = next()) {
            null, Line.Ended -> return PhotoMigrations.Verdict(
                PhotoMigrations.State.UNKNOWN,
                "log stream never attached",
            )
            is Line.Read -> log.appendText("${header.text}\n")
        }
        val past = shell.ok(logCommand + pastLogArguments(bootedAt), bound)
        past.lines().filter { it.isNotBlank() }.forEach(::take)
        var verdict = state.verdict()
        while (verdict.state == PhotoMigrations.State.WAITING) {
            verdict = when (val line = next()) {
                null -> return verdict.copy(state = PhotoMigrations.State.UNKNOWN, why = "${verdict.why} after $bound")
                Line.Ended -> return verdict.copy(state = PhotoMigrations.State.UNKNOWN, why = "log stream ended while ${verdict.why}")
                is Line.Read -> take(line.text).let { state.verdict() }
            }
        }
        verdict
    } finally {
        stream.destroyForcibly()
    }
}

/**
 * `log show`'s arguments for the past of the boot that began after [bootedAt]: `--start @<epoch>`, never a fixed
 * `--last`, which reaches into the boot before (see [PhotoMigrations]). Not `--last boot` either: inside a simulator it
 * returned the previous boot's lines after a reboot (measured on macos-26, 2026-11-05), while `--start @<epoch>` taken
 * before `simctl boot` returned none of them. The simulator runs on the host's clock, and an epoch has no time zone.
 * Truncated to the second, so the window opens at most a second early — before the boot, so nothing of it is missed.
 */
internal fun pastLogArguments(bootedAt: Instant): List<String> =
    listOf(
        "show",
        "--style",
        "compact",
        "--start",
        "@${bootedAt.epochSecond}",
        "--predicate",
        PhotoMigrations.PREDICATE,
    )

private sealed interface Line {
    data class Read(val text: String) : Line

    data object Ended : Line
}
