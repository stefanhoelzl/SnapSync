package app.snapsync.application.process

import app.snapsync.application.EntryPointCall
import app.snapsync.data.foreground.AppForeground
import app.snapsync.data.process.FootprintReading
import app.snapsync.data.process.FootprintTrail
import app.snapsync.domain.process.FootprintTrailRule

/**
 * Read the app's own memory footprint into the trail the next process-metric report carries — only away from the
 * foreground ([FootprintTrailRule.keeps]). A platform with no footprint reading records nothing.
 */
class RecordFootprint(
    private val foreground: AppForeground,
    private val reading: FootprintReading,
    private val trail: FootprintTrail,
) {
    /** Record the footprint at [moment] — as the app enters the background, or a wake's work ends there. */
    @EntryPointCall
    operator fun invoke(moment: String) {
        if (!FootprintTrailRule.keeps(foreground.inFront.value)) return
        reading.now()?.let { trail.record(moment, it) }
    }
}
