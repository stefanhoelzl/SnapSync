package app.snapsync.journeys.device

import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * One Android device — the emulator `journeys (android)` boots — over `adb -s <serial>`: every command names its device,
 * so a phone attached beside it is never reached. What the device's systems answer is read here once per call; a
 * wait on one is the caller's, bounded.
 */
class AndroidDriver(private val shell: Shell, val adb: String, val serial: String) {

    /** Whether the system finished booting (`sys.boot_completed`). */
    fun booted(): Boolean = run("shell", "getprop", "sys.boot_completed").out.trim() == "1"

    /**
     * Waits, on the device, until its queued broadcasts are delivered — DownloadManager's own BOOT_COMPLETED pass among
     * them, which reschedules every row it holds: a journey started before it downloads through the race AndroidDownload
     * has to repair, not the path a member's phone takes (CI run 37005952143).
     */
    fun awaitBroadcastsIdle() {
        ok(LONG, "shell", "am", "wait-for-broadcast-idle")
    }

    fun clearLog() {
        ok(SHORT, "logcat", "-c")
    }

    /** The whole log so far, every buffer. */
    fun log(): String = run("logcat", "-d", within = LONG).out

    fun uninstall(app: String) {
        run("uninstall", app, within = LONG)
    }

    fun install(apk: File) {
        ok(LONG, "install", "-r", apk.path)
    }

    fun grant(app: String, permission: String) {
        ok(SHORT, "shell", "pm", "grant", app, "android.permission.$permission")
    }

    /** The device's [port] on loopback is the host's: the local backend, reached from the device's own 127.0.0.1. */
    fun reverse(port: Int) {
        ok(SHORT, "reverse", "tcp:$port", "tcp:$port")
    }

    /** The host's [host] port reaches the device's [device] port: the app's control channel. */
    fun forward(host: Int, device: Int) {
        ok(SHORT, "forward", "tcp:$host", "tcp:$device")
    }

    fun removeForward(host: Int) {
        run("forward", "--remove", "tcp:$host")
    }

    /** Starts [activity] (`<package>/<class>`) and answers once it has drawn (`am start -W`). */
    fun start(activity: String) {
        ok(LONG, "shell", "am", "start", "-W", "-n", activity)
    }

    /** What a person's "Force stop" in Settings does: the process ends, and the system forgets its scheduled work. */
    fun forceStop(app: String) {
        ok(SHORT, "shell", "am", "force-stop", app)
    }

    /** The home screen, as a person leaving the app opens it: the app's screen goes to the background. */
    fun home() {
        ok(SHORT, "shell", "input", "keyevent", "KEYCODE_HOME")
    }

    /**
     * What a person's swipe of [app]'s card out of the recent apps does: its task is removed, and with it the process,
     * while the system keeps its scheduled work. `am stack remove` is the system's own removal of a task: it leaves the
     * exit record a removal from the launcher's Recents leaves — `USER REQUESTED`, `REMOVE TASK` — where `am kill`
     * leaves `KILL BACKGROUND` and `am force-stop` `FORCE STOP` (measured 2026-11-07, API 36). A swipe injected into
     * Recents reaches the launcher but does not dismiss the card, so the gesture itself is not played.
     */
    fun removeTask(app: String) {
        val recents = ok(SHORT, "shell", "dumpsys", "activity", "recents")
        val task = checkNotNull(taskOf(recents, app)) { "$app has no task in the recent apps: $recents" }
        ok(SHORT, "shell", "am", "stack", "remove", task.toString())
    }

    /** Whether [app]'s process is running. */
    fun running(app: String): Boolean = pid(app) != null

    /** [app]'s process id, or `null` while it is not running. */
    fun pid(app: String): Int? = run("shell", "pidof", app).out.trim().split(' ').first().toIntOrNull()

    /**
     * The jobs JobScheduler holds for [app] that wait for a time — a WorkManager request with a delay is a job with a
     * minimum latency: the heartbeat, busy or idle — as `dumpsys jobscheduler` lists them.
     */
    fun delayedJobs(app: String): Int =
        ok(SHORT, "shell", "dumpsys", "jobscheduler", app).lineSequence().count { "Minimum latency" in it }

    /**
     * The jobs of [app] JobScheduler is running or about to run, as `dumpsys jobscheduler` lists them: each `Slot #…` of
     * its `Active jobs:` and each entry of its `Pending queue:` (`N/A` and `None` when empty, measured 2026-11-08, API 36).
     */
    fun jobsInFlight(app: String): Int = JobsInFlight.count(ok(SHORT, "shell", "dumpsys", "jobscheduler", app), app)

    /** The jobs JobScheduler holds for [app] that wait for a change to a content URI: the library-change wake. */
    fun libraryWatches(app: String): Int =
        ok(SHORT, "shell", "dumpsys", "jobscheduler", app).lineSequence().count { "Trigger content URIs" in it }

    /** How [app]'s last process ended, as the system recorded it (`ApplicationExitInfo`) — `null` before any did. */
    fun lastExit(app: String): ExitRecord? = ExitRecord.parse(
        ok(SHORT, "shell", "dumpsys", "activity", "exit-info", app),
    )

    /** The window that has the input focus, as the window manager names it: the app on the screen, or the launcher. */
    fun focusedWindow(): String =
        ok(SHORT, "shell", "dumpsys", "window").lineSequence().firstOrNull { "mCurrentFocus" in it }?.trim().orEmpty()

    /**
     * [photo] into the device's camera folder (`DCIM/Camera/<name>`), published to MediaStore by a synchronous scan:
     * pushed alone it is a PENDING row no app sees. Answers its row's published state, read once.
     */
    fun takePhoto(photo: File, name: String): MediaRow {
        val path = "$CAMERA/$name"
        ok(SHORT, "push", photo.path, path)
        ok(SHORT, "shell", "content", "call", "--uri", "content://media", "--method", "scan_file", "--arg", path)
        val row = ok(
            SHORT, "shell", "content", "query", "--uri", "content://media/external/images/media",
            // `adb shell` hands its words to the device's shell, which would strip bare quotes: the clause is quoted
            // for it, so the query gets its own.
            "--projection", "_id:is_pending:datetaken", "--where", "\"_display_name='$name'\"",
        )
        return checkNotNull(MediaRow.parse(row)) { "MediaStore has no row for $path after its scan: $row" }
    }

    /** The PNG of what the screen shows. */
    fun screenshot(into: File) {
        if (!shell.runInto(adb("exec-out", "screencap", "-p"), into, SHORT).ok) into.delete()
    }

    private fun run(vararg args: String, within: Duration = SHORT): Answer = shell.run(adb(*args), within)

    private fun ok(within: Duration, vararg args: String): String = shell.ok(adb(*args), within)

    private fun adb(vararg args: String) = listOf(adb, "-s", serial, *args)

    /** A MediaStore image row, as `content query` prints it: `Row: 0 _id=456, is_pending=0, datetaken=1792929600000`. */
    data class MediaRow(val id: Long, val pending: Boolean, val takenMillis: Long?) {
        companion object {
            fun parse(text: String): MediaRow? {
                val fields = text.lineSequence().firstOrNull { it.startsWith("Row:") }
                    ?.substringAfter(' ')?.substringAfter(' ')
                    ?.split(", ")?.associate { it.substringBefore('=') to it.substringAfter('=') }
                    ?: return null
                return MediaRow(
                    id = fields["_id"]?.toLongOrNull() ?: return null,
                    pending = fields["is_pending"] == "1",
                    takenMillis = fields["datetaken"]?.toLongOrNull(),
                )
            }
        }
    }

    /**
     * One process exit as `dumpsys activity exit-info` prints it, newest first:
     * `timestamp=… pid=4571 realUid=…` then `process=app.snapsync reason=10 (USER REQUESTED) subreason=22 (REMOVE TASK)`.
     */
    data class ExitRecord(val pid: Int, val reason: Int, val subreason: Int, val subreasonName: String) {
        companion object {
            /** `ApplicationExitInfo.REASON_USER_REQUESTED`. */
            const val USER_REQUESTED = 10

            /** The system's subreason for a task removed from the recent apps. */
            const val REMOVE_TASK = 22

            private val PID = Regex("""\bpid=(\d+)""")
            private val REASON = Regex("""\breason=(\d+) .*\bsubreason=(\d+) \(([^)]*)\)""")

            fun parse(text: String): ExitRecord? {
                val lines = text.lines()
                val at = lines.indexOfFirst { REASON.containsMatchIn(it) }.takeIf { it >= 0 } ?: return null
                val pid = lines.take(at + 1).lastOrNull { PID.containsMatchIn(it) }?.let { PID.find(it) }
                    ?.groupValues?.get(1)?.toInt() ?: return null
                val (reason, subreason, name) = REASON.find(lines[at])!!.destructured
                return ExitRecord(pid, reason.toInt(), subreason.toInt(), name)
            }
        }
    }

    internal companion object {
        /** The id of [app]'s task in `dumpsys activity recents`: `Recent #0: Task{64d1a65 #165 type=standard A=10222:app}`. */
        fun taskOf(recents: String, app: String): Int? =
            Regex("""Task\{\S+ #(\d+) [^}]*\bA=\d+:${Regex.escape(app)}}""").find(recents)?.groupValues?.get(1)?.toInt()

        const val CAMERA = "/sdcard/DCIM/Camera"
        val SHORT = 30.seconds
        val LONG = 5.minutes
    }
}
