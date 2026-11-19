package app.snapsync.application.upload

import app.snapsync.application.EntryPointCall
import app.snapsync.data.gallery.FullGrantRead
import app.snapsync.data.gallery.GrantRead
import app.snapsync.data.upload.ExtensionRegistrar
import app.snapsync.data.upload.SessionEnds
import app.snapsync.data.wake.WakeArming
import app.snapsync.domain.membership.EndsUploads
import app.snapsync.inbound.Log

private val log = Log("StopUploads")

/**
 * **Stop every upload of a membership whose uploads end** — only with the evidence they do ([EndsUploads]: a committed
 * leave, a store that holds no membership after a leave that did not finish, an event whose photos are deleted): the
 * extension deregistered — wiping its in-flight OS jobs — the app's wakes cancelled, and its live transfers cancelled
 * with their staged files. The rows are left: they belong to the event, and the next join's purge removes them.
 *
 * The extension is deregistered only with a full grant in hand, read now, as it is registered: without one iOS cannot
 * be asked. It refuses under a partial grant (3311), and with no grant at all iOS 27 answers codes no SDK declares.
 * Skipping the write leaves nothing live: the extension's entry gate withholds without a full grant, and the next
 * join's registration replaces whatever record survives.
 */
class StopUploads(
    private val registrar: ExtensionRegistrar,
    private val grant: GrantRead,
    private val arming: WakeArming,
    private val ends: SessionEnds,
) {
    @EntryPointCall
    suspend operator fun invoke(ending: EndsUploads) {
        deregistered(ending)
        arming.cancelAll()
        ends.cancelAll()
        log.i("uploads stopped")
    }

    private suspend fun deregistered(ending: EndsUploads) = when (val full = grant.full()) {
        is FullGrantRead.Full -> registrar.deregister(ending, full.grant)
        FullGrantRead.NotFull -> log.i("extension not deregistered: the photo grant is not full")
    }
}
