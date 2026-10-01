package app.snapsync.screenshots

import java.io.File
import kotlin.time.Duration.Companion.seconds

/**
 * The booted Android emulator the DEBUG rig build is installed on, and the `adb` calls a capture makes. The app's files
 * live in its private storage on the device, reached through `run-as` — which is why the build must be debuggable (the
 * rig release build is not).
 *
 * On construction it forwards [port] to the app's control channel and puts the status bar in SystemUI's demo mode:
 * 9:41, a full battery, full Wi-Fi, no cellular icon and no notification icons, so no shot renders the wall clock or a
 * stray alert.
 */
internal class AndroidEmulator(private val serial: String, override val port: Int) : CaptureHost {
    private val adb = System.getenv("ADB") ?: "${System.getenv("ANDROID_HOME")}/platform-tools/adb"

    init {
        adb("forward", "tcp:$port", "tcp:$APP_PORT")
        adb("shell", "settings", "put", "global", "sysui_demo_allowed", "1")
        DEMO.forEach { extras -> adb("shell", "am", "broadcast", "-a", "com.android.systemui.demo", *extras.toTypedArray()) }
    }

    override suspend fun launch() {
        adb("shell", "am", "start", "-W", "-n", "$PACKAGE/$ACTIVITY")
        within(90.seconds, "the app never answered on its rig port $port") { healthy() }
    }

    override suspend fun terminate() {
        adb("shell", "am", "force-stop", PACKAGE)
        awaitGone()
    }

    override suspend fun awaitGone() {
        within(20.seconds, "the app was still running") { attempt("shell", "pidof", PACKAGE) == null }
    }

    /** The system-wide night mode, which the activity's configuration change carries to Compose's dark theme. */
    override fun appearance(dark: Boolean) {
        adb("shell", "cmd", "uimode", "night", if (dark) "yes" else "no")
    }

    override fun screenshot(to: File) {
        val process = ProcessBuilder(adb, "-s", serial, "exec-out", "screencap", "-p").redirectOutput(to).start()
        val error = process.errorStream.bufferedReader().readText()
        check(process.waitFor() == 0 && to.length() > 0) { "screencap failed: $error" }
    }

    override fun read(path: String): String? = attempt("shell", "run-as", PACKAGE, "cat", path)

    override fun delete(path: String) {
        adb("shell", "run-as", PACKAGE, "rm", "-rf", path)
    }

    private fun adb(vararg args: String): String = run(listOf(adb, "-s", serial) + args)

    /** [args]'s output, or null if adb (or the command it ran) failed. */
    private fun attempt(vararg args: String): String? = runCatching { adb(*args) }.getOrNull()

    private companion object {
        const val PACKAGE = "app.snapsync"
        const val ACTIVITY = "app.snapsync.android.MainActivity"

        /** The rig's control channel inside the app (`snapsync-android`). */
        const val APP_PORT = 18099

        /** SystemUI demo-mode commands, each a broadcast's extras. */
        val DEMO = listOf(
            listOf("-e", "command", "enter"),
            listOf("-e", "command", "clock", "-e", "hhmm", "0941"),
            listOf("-e", "command", "battery", "-e", "level", "100", "-e", "plugged", "false"),
            listOf("-e", "command", "network", "-e", "wifi", "show", "-e", "level", "4", "-e", "fully", "true"),
            listOf("-e", "command", "network", "-e", "mobile", "hide"),
            listOf("-e", "command", "notifications", "-e", "visible", "false"),
        )
    }
}
