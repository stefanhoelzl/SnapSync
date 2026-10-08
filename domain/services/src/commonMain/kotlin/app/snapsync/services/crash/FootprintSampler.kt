package app.snapsync.services.crash

import app.snapsync.ports.ProcessInfo

/**
 * Reads the app's own memory footprint into the [trail] the next process-metric report carries (capability
 * `privacy-security`) — only while the app is not [foregrounded]: the background is where the platform ends a process
 * for memory, and the last reading before a suspension is the one a report about that suspension needs, so foreground
 * readings would only push it out of the trail. A platform with no footprint reading records nothing.
 */
class FootprintSampler(
    private val processInfo: ProcessInfo,
    private val trail: FootprintTrail,
    private val foregrounded: () -> Boolean,
) {
    /** Record the footprint at [moment] — as the app enters the background, or a wake's work ends there. */
    fun record(moment: String) {
        if (foregrounded()) return
        processInfo.memoryFootprint()?.let { trail.record(moment, it) }
    }
}
