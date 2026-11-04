package app.snapsync.application.sharing

import app.snapsync.application.EntryPointCall
import app.snapsync.data.membership.active
import app.snapsync.data.membership.sharedOver
import app.snapsync.data.sharing.ManifestBody
import app.snapsync.data.sharing.ManifestRecord
import app.snapsync.data.sharing.emptyManifest
import app.snapsync.data.time.WallClock
import app.snapsync.domain.sharing.SettleNeed
import app.snapsync.domain.sharing.SettlingLook
import app.snapsync.domain.sharing.ShareDeclaration
import app.snapsync.domain.sharing.SharePhase
import app.snapsync.mock.ClockMock
import app.snapsync.mock.DatabasesMock
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/**
 * Whether the end of a wake owes the share a settling look (capability `event-lifetime`): once the member's own range
 * has ended, until a settled share was sent — and not while the record cannot be read.
 */
@OptIn(EntryPointCall::class)
class CheckSettleNeedTest {

    private val databases = DatabasesMock()
    private val record = ManifestRecord(sharedOver(databases))
    private val clock = ClockMock(Instant.parse("2026-07-16T00:00:00Z"))
    private val need = CheckSettleNeed(record, WallClock(clock.port()))

    private fun sent(declaration: ShareDeclaration) {
        val manifest = emptyManifest(declaration = declaration)
        record.record(active().event.id, manifest.version, declaration, 0, ManifestBody.of(manifest))
    }

    @Test
    fun `nothing is owed while the range runs`() = runTest {
        assertEquals(SettleNeed.NOT_DUE, need(active()))
    }

    @Test
    fun `after the end a look is owed until a settled share was sent`() = runTest {
        clock.now = Instant.parse("2026-07-22T09:00:00Z")
        assertEquals(SettleNeed.DUE, need(active()), "nothing was ever sent")

        sent(ShareDeclaration.Sharing)
        assertEquals(SettleNeed.DUE, need(active()), "a sharing one was sent")

        sent(ShareDeclaration.of(SharePhase.of(active().share.range, clockNow()), SettlingLook.NothingToLook))
        assertEquals(SettleNeed.NOT_DUE, need(active()))
    }

    @Test
    fun `a record that cannot be read owes nothing now — the next wake asks again`() = runTest {
        clock.now = Instant.parse("2026-07-22T09:00:00Z")
        databases.lock()
        assertEquals(SettleNeed.NOT_DUE, need(active()))
    }

    private fun clockNow() = WallClock(clock.port()).now()
}
