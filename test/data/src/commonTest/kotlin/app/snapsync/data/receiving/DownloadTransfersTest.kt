package app.snapsync.data.receiving

import app.snapsync.inbound.Trigger
import app.snapsync.ports.StartResult
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The platform's background downloads as receiving keeps them (capability `receiving-photos`): every plan hands over
 * its whole pending snapshot, a key is queued at most once and never both queued and running, an address no session
 * can fetch is skipped, and one the platform did not start stays pending for a later read.
 */
class DownloadTransfersTest {
    private val device = Store()

    @Test
    fun `a running transfer is never started again by a later read`() = runTest {
        val a = photo(ref("A"))
        device.planned(page(a))
        device.planned(page(a))
        assertEquals(1, device.download.started.size)
    }

    @Test
    fun `a download carries when it was queued and the entry point that queued it`() = runTest {
        withContext(Trigger.of("OnSilentPush")) { device.planned(page(photo(ref("A")))) }
        device.planned(page(photo(ref("B"))))

        val (pushed, plain) = device.download.headers
        assertEquals("2026-07-14T12:00:00Z", pushed["x-snapsync-queued"])
        assertEquals("OnSilentPush", pushed["x-snapsync-queued-by"])
        assertEquals(null, plain["x-snapsync-queued-by"], "queued off an entry point, it names none")
    }

    @Test
    fun `an address no session can fetch is skipped and stays pending`() = runTest {
        val bad = photo(ref("BAD"), captured(), resource("bad-primary.heic", url = "file:///etc/passwd"))
        val hostless = photo(ref("HOSTLESS"), captured(), resource("h-primary.heic", url = "https:///nothing"))

        val planned = device.planned(page(bad, hostless))

        assertEquals(2, planned.pending, "both handed over")
        assertEquals(emptyList(), device.download.started)
        assertEquals("PENDING", device.state(bad.ref))
    }

    @Test
    fun `a transfer the platform did not start is started by the next read`() = runTest {
        val a = photo(ref("A"))
        device.download.answer = StartResult.NotStarted
        device.planned(page(a))
        assertEquals(1, device.download.started.size)

        device.download.answer = StartResult.Started
        device.planned(page(a))
        assertEquals(2, device.download.started.size, "it held no slot")
        device.planned(page(a))
        assertEquals(2, device.download.started.size, "now it runs")
    }
}
