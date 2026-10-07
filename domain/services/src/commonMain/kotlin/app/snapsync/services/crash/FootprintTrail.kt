package app.snapsync.services.crash

import app.snapsync.model.FileArea
import app.snapsync.model.FileResult
import app.snapsync.model.FootprintSample
import app.snapsync.model.MemoryFootprint
import app.snapsync.model.appendedFootprint
import app.snapsync.model.decodeFootprintTrail
import app.snapsync.model.encodeFootprintTrail
import app.snapsync.model.footprintFields
import app.snapsync.ports.Clock
import app.snapsync.ports.Files
import co.touchlab.kermit.Logger

/**
 * The app's last few readings of its own memory footprint (capability `privacy-security`), kept across processes so
 * that the process-metric report delivered on a LATER launch — the one saying a suspended app was ended for memory —
 * arrives with what the ended process last measured of itself ([ProcessAccount]).
 *
 * A file in the PRIVATE area: only the app records and only the app's metric reports read it. Written atomically by
 * every `Files` adapter, so a read racing a write sees one trail or the other.
 *
 * **Never raises.** A refused write is logged and dropped, and an unreadable trail reads as none: a lost reading costs
 * an explanation, never a report.
 */
class FootprintTrail(
    private val files: Files,
    private val clock: Clock,
    private val log: Logger = Logger.withTag("footprint"),
) {

    /** Record [footprint] as read at [moment], and write it to the device log too. */
    fun record(moment: String, footprint: MemoryFootprint) {
        val sample = FootprintSample(clock.now(), moment, footprint)
        log.i { "memory: ${sample.describe()}" }
        val written = files.write(
            FileArea.PRIVATE,
            PATH,
            encodeFootprintTrail(appendedFootprint(load(), sample)).encodeToByteArray(),
        )
        if (written !is FileResult.Ok) log.w { "the footprint reading was not kept ($written)" }
    }

    /** The trail as a report's context carries it — empty where nothing was recorded or nothing could be read. */
    fun fields(): Map<String, String> = footprintFields(load())

    private fun load(): List<FootprintSample> = when (val read = files.read(FileArea.PRIVATE, PATH)) {
        is FileResult.Ok -> decodeFootprintTrail(read.value.decodeToString())
        else -> emptyList()
    }

    private companion object {
        const val PATH = "process-metrics/footprints.json"
    }
}
