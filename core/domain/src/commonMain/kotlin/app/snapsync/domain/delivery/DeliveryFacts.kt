package app.snapsync.domain.delivery

import app.snapsync.domain.mobiledata.PhotoNetwork
import app.snapsync.domain.network.NetworkAccess
import app.snapsync.domain.report.Platform
import app.snapsync.domain.sharing.PhotoAccess

/**
 * **The delivery context's words** (decision record `changes/delivery-record`, D2/D8): how each fact the app reads is
 * written into the one header every request carries for the service's request log, `<key>=<value>;…` — the keys and
 * values the service's record documents (`docs/architecture.md`, "The delivery record"). A fact that cannot be read is
 * left out, never guessed.
 */
object DeliveryFacts {
    /** The header the context rides in. */
    const val HEADER: String = "x-snapsync-context"

    /** The header for [facts] in their order — none when there are none. */
    fun header(facts: List<Pair<String, String>>): Map<String, String> = when (facts.isEmpty()) {
        true -> emptyMap()
        false -> mapOf(HEADER to facts.joinToString(";") { (key, value) -> "$key=${clean(value)}" })
    }

    /**
     * `os`: the platform (`ios`), and `os_version`: the version number its OS states (`18.6`) — no `os_version` when it
     * states none.
     */
    fun os(platform: Platform, osVersion: String): Map<String, String> =
        mapOf("os" to platform.tag) + VERSION.find(osVersion)?.let { mapOf("os_version" to it.value) }.orEmpty()

    /** `flavour`: `release` for a build that reports as production, `dev` for any other — all a build knows. */
    fun flavour(reporterEnvironment: String): String = when (reporterEnvironment) {
        PRODUCTION -> "release"
        else -> "dev"
    }

    /**
     * `build`: the build number the build carries (decision record `changes/build-number-in-delivery-record`), when it
     * reads as one — nothing for an unreadable one (`?`).
     */
    fun build(buildNumber: String): Map<String, String> =
        buildNumber.takeIf { BUILD.matches(it) }?.let { mapOf("build" to it) }.orEmpty()

    /** `access`: the photo access the member gave. */
    fun access(access: PhotoAccess): String = when (access) {
        PhotoAccess.FULL -> "full"
        PhotoAccess.LIMITED -> "limited"
        PhotoAccess.NONE -> "none"
    }

    /** `mobile_data`: whether photos may use a restricted network. */
    fun mobileData(network: PhotoNetwork): String = flag(network == PhotoNetwork.ANY)

    /** `network`, from the platform's answer — nothing when it gave none in time. */
    fun network(access: NetworkAccess?): Map<String, String> = when (access) {
        null -> emptyMap()
        is NetworkAccess.Online -> mapOf("network" to restricted(access.restricted))
        NetworkAccess.Offline -> mapOf("network" to "offline")
        NetworkAccess.Blocked -> mapOf("network" to "blocked")
    }

    private fun restricted(restricted: Boolean): String = when (restricted) {
        true -> "restricted"
        false -> "unrestricted"
    }

    /** A yes/no as the header writes it. */
    internal fun flag(on: Boolean): String = when (on) {
        true -> "1"
        false -> "0"
    }

    /** A value as the header may carry it: the header's own separators would split it. */
    private fun clean(value: String): String = value.filterNot { it == ';' || it == '=' }.trim()

    private const val PRODUCTION = "production"
    private val VERSION = Regex("""\d+(\.\d+)*""")
    private val BUILD = Regex("""[0-9]{1,10}""")
}
