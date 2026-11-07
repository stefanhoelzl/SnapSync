package app.snapsync.data.process

import app.snapsync.domain.process.FootprintSample
import app.snapsync.domain.process.FootprintTrailRule
import app.snapsync.domain.process.MemoryFootprint
import app.snapsync.format.FootprintTrailText
import app.snapsync.inbound.Log
import app.snapsync.inbound.of
import app.snapsync.ports.Clock
import app.snapsync.ports.FileAccess
import app.snapsync.ports.FileArea
import app.snapsync.ports.FileFailure
import app.snapsync.ports.FileResult
import app.snapsync.ports.Files
import app.snapsync.ports.ProcessInfo

/**
 * The app's last few readings of its own memory footprint, kept across processes so that the process-metric report
 * delivered on a LATER launch — the one saying a suspended app was ended for memory — arrives with what the ended
 * process last measured of itself.
 *
 * A file in the PRIVATE area: only the app records and only the app's metric reports read it. Written atomically by
 * every `Files` adapter, so a read racing a write sees one trail or the other.
 *
 * **Never raises.** A refused write is logged and dropped, and an unreadable trail reads as none: a lost reading costs
 * an explanation, never a report.
 */
class FootprintTrail(private val files: Files, private val clock: Clock) {
    private val log = Log("footprint").unlabelled

    /** Record [footprint] as read at [moment], and write it to the device log too. */
    fun record(moment: String, footprint: MemoryFootprint) {
        val sample = FootprintSample(clock.now(), moment, footprint)
        log.i("memory", "reading" of sample.describe())
        val trail = FootprintTrailText.encode(FootprintTrailRule.appended(load(), sample))
        kept(files.write(FileArea.PRIVATE, PATH, trail.encodeToByteArray()))
    }

    private fun kept(written: FileAccess<Unit>) = when (written) {
        is FileResult.Ok -> Unit
        is FileFailure -> log.w("the footprint reading was not kept", "write" of written)
    }

    /** The trail as a report's context carries it — empty where nothing was recorded or nothing could be read. */
    fun fields(): Map<String, String> = FootprintTrailRule.fields(load())

    private fun load(): List<FootprintSample> = when (val read = files.read(FileArea.PRIVATE, PATH)) {
        is FileResult.Ok -> FootprintTrailText.decode(read.value.decodeToString())
        is FileFailure -> emptyList()
    }

    companion object {
        /** Where the trail is kept, in the private area: diagnostics, which the rig leaves out of the app's storage. */
        const val PATH: String = "process-metrics/footprints.json"
    }
}

/** The app's own memory footprint, read now — `null` on a platform that reads none. */
class FootprintReading(private val info: ProcessInfo) {
    fun now(): MemoryFootprint? = info.memoryFootprint()
}
