package app.snapsync.screenshots

import java.io.File
import kotlin.time.Duration.Companion.seconds

/**
 * The booted iOS simulator the app is installed on, and the `simctl` calls a capture makes. The app's App-Group
 * container is on this Mac's own disk, so its files are read and deleted directly.
 */
internal class Simulator(private val udid: String, override val port: Int) : CaptureHost {

    override suspend fun launch() {
        simctl("launch", udid, BUNDLE, env = mapOf("SIMCTL_CHILD_SNAPSYNC_RIG_PORT" to port.toString()))
        within(90.seconds, "the app never answered on its rig port $port") { healthy() }
    }

    /** It ignores SIGTERM, and a launch over a live one renders black — hence the wait. */
    override suspend fun terminate() {
        runCatching { simctl("terminate", udid, BUNDLE) }
        awaitGone()
    }

    override suspend fun awaitGone() {
        within(20.seconds, "the app was still running") { BUNDLE !in simctl("spawn", udid, "launchctl", "list") }
    }

    override fun appearance(dark: Boolean) {
        simctl("ui", udid, "appearance", if (dark) "dark" else "light")
    }

    override fun screenshot(to: File) {
        simctl("io", udid, "screenshot", to.path)
    }

    override fun read(path: String): String? = File(path).takeIf { it.isFile }?.readText()

    override fun delete(path: String) {
        File(path).deleteRecursively()
    }

    private fun simctl(vararg args: String, env: Map<String, String> = emptyMap()): String =
        run(listOf("xcrun", "simctl") + args, env)

    private companion object {
        const val BUNDLE = "app.snapsync"
    }
}
