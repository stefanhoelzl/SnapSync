package app.snapsync.journeys.device

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The iOS simulators, over `xcrun simctl` — and `applesimutils` for the photo grant: `simctl privacy grant photos`
 * writes a TCC row PhotoKit never consults, so only `applesimutils` grants a simulator app the library. What a
 * simulator answers is read here once per call; a wait on one is the caller's, bounded.
 */
class SimulatorDriver(private val shell: Shell) {

    /**
     * The runner image's own iPhone 17 on its newest iOS runtime, by UDID — `null` when the image carries none. The
     * image booted it once when it was built, so it skips the first-boot work a created device pays.
     */
    fun imageIphone(): String? {
        val devices = Json.parseToJsonElement(simctl(SHORT, "list", "-j", "devices", "available")).jsonObject
            .getValue("devices").jsonObject
        return devices.keys.filter { ".SimRuntime.iOS-" in it }
            .sortedWith(
                compareByDescending<String> { version(it)[0] }.thenByDescending { version(it).getOrElse(1) { 0 } },
            )
            .firstNotNullOfOrNull { runtime ->
                devices.getValue(runtime).jsonArray.map { it.jsonObject }
                    .firstOrNull { it["name"]?.jsonPrimitive?.content == IPHONE }?.get("udid")?.jsonPrimitive?.content
            }
    }

    fun create(name: String): String = simctl(SHORT, "create", name, IPHONE).trim()

    fun delete(udid: String) {
        shell.run(listOf("xcrun", "simctl", "delete", udid), SHORT)
    }

    /** Boots [udid] and answers once it has finished booting (`bootstatus -b`). */
    fun boot(udid: String) {
        simctl(SHORT, "boot", udid)
        simctl(BOOT, "bootstatus", udid, "-b")
    }

    fun shutdown(udid: String) {
        shell.run(listOf("xcrun", "simctl", "shutdown", udid), BOOT)
    }

    /**
     * Stops [daemons] on booted [udid] for the rest of its life — `disable` keeps launchd from starting one again, across
     * reboots, and `bootout` ends a running one — and answers those still loaded. Never fatal: a simulator that keeps
     * one is only slower.
     */
    fun stopDaemons(udid: String, daemons: List<String>): List<String> {
        daemons.forEach {
            launchctl(udid, "disable", "system/com.apple.$it")
            launchctl(udid, "bootout", "system/com.apple.$it")
        }
        val loaded = launchctl(udid, "list").out.lines().mapNotNull { it.split(Regex("\\s+")).getOrNull(2) }.toSet()
        return daemons.filter { "com.apple.$it" in loaded }
    }

    fun install(udid: String, app: File) {
        simctl(LONG, "install", udid, app.path)
    }

    /** Launches [bundle] on [udid], its process's environment carrying [env] (as `SIMCTL_CHILD_<name>`). */
    fun launch(udid: String, bundle: String, env: Map<String, String> = emptyMap()) {
        shell.ok(listOf("xcrun", "simctl", "launch", udid, bundle), LONG, env.mapKeys { "SIMCTL_CHILD_${it.key}" })
    }

    fun terminate(udid: String, bundle: String) {
        shell.run(listOf("xcrun", "simctl", "terminate", udid, bundle), SHORT)
    }

    /** [bundle]'s data container on [udid]: where the app's `Documents/` — its log, its published rig port — lives. */
    fun dataContainer(
        udid: String,
        bundle: String,
    ): File = File(simctl(SHORT, "get_app_container", udid, bundle, "data").trim())

    fun screenshot(udid: String, into: File) {
        shell.run(listOf("xcrun", "simctl", "io", udid, "screenshot", into.path), SHORT)
    }

    /** [bundle]'s photo access on [udid] — `YES`, `limited`, `NO` or `unset` — granted as the OS records a person's answer. */
    fun grantPhotos(udid: String, bundle: String, access: String) {
        shell.ok(
            listOf("applesimutils", "--byId", udid, "--bundle", bundle, "--setPermissions", "photos=$access"),
            SHORT,
        )
    }

    private fun launchctl(udid: String, vararg args: String): Answer =
        shell.run(listOf("xcrun", "simctl", "spawn", udid, "launchctl", *args), SHORT)

    private fun simctl(within: Duration, vararg args: String): String = shell.ok(
        listOf("xcrun", "simctl", *args),
        within,
    )

    private fun version(runtime: String): List<Int> = runtime.substringAfterLast("iOS-").split('-').map { it.toInt() }

    companion object {
        const val IPHONE = "iPhone 17"
        private val SHORT = 60.seconds
        private val LONG = 5.minutes
        private val BOOT = 10.minutes
    }
}
