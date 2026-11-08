package app.snapsync.composition.app

import app.snapsync.data.time.WallClock
import app.snapsync.ports.Clock
import app.snapsync.presentation.CutoffFormatter
import app.snapsync.query.time.WallClockInstant
import kotlinx.datetime.TimeZone

/**
 * **The process's one screen clock** — the [CutoffFormatter] a root builds before its composition, so the Ui adapter
 * and the status host format with the same instance: the device's [clock], and the [zone] the root read once.
 */
class ScreenClockGraph(clock: Clock, zone: TimeZone) {
    val formatter: CutoffFormatter = CutoffFormatter(WallClockInstant(WallClock(clock)), zone)
}
