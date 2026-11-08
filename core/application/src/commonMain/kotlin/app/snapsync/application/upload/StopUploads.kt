package app.snapsync.application.upload

import app.snapsync.application.EntryPointCall
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
 */
class StopUploads(
    private val registrar: ExtensionRegistrar,
    private val arming: WakeArming,
    private val ends: SessionEnds,
) {
    @EntryPointCall
    suspend operator fun invoke(ending: EndsUploads) {
        registrar.deregister(ending)
        arming.cancelAll()
        ends.cancelAll()
        log.i("uploads stopped")
    }
}
