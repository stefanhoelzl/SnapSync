package app.snapsync.rig

import app.snapsync.contracts.EntryDriver
import app.snapsync.levers.AtRelease
import app.snapsync.levers.OsLevers
import app.snapsync.levers.Released
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.time.TimeSource

/**
 * The `os` service ([OsLevers]) over a host's [driver] (`docs/testing.md`, "Typed services"): each delivery one
 * [EntryDriver] delivery, on [lane] — the lane the platform delivers on, as Swift calls the app root's entry points on
 * main.
 *
 * A delivery the operating system hands a completion handler answers once delivered, or — `awaitRelease` `true` —
 * once the app released the handler, with how long it held it and what the device held then ([atRelease], read on the
 * releasing thread).
 */
class PlayedEntries(
    private val driver: EntryDriver,
    private val lane: CoroutineContext,
    private val atRelease: () -> AtRelease,
) : OsLevers {
    override suspend fun foreground() = withContext(lane) { driver.foreground() }

    override suspend fun background() = withContext(lane) { driver.background() }

    override suspend fun pushToken(token: String) = withContext(lane) { driver.pushToken(token) }

    override suspend fun pushTokenFailure(description: String) =
        withContext(lane) { driver.pushTokenFailure(description) }

    override suspend fun continueLink(url: String) = withContext(lane) { driver.continueLink(url) }

    override suspend fun silentPush(event: String?, awaitRelease: Boolean?): Released? =
        receipted(awaitRelease) { done -> driver.silentPush(event, done) }

    override suspend fun backgroundTask(identifier: String, awaitRelease: Boolean?): Released? =
        receipted(awaitRelease) { done -> driver.backgroundTask(identifier, done) }

    override suspend fun backgroundTransfers(identifier: String, awaitRelease: Boolean?): Released? =
        receipted(awaitRelease) { done -> driver.backgroundTransfers(identifier, done) }

    private suspend fun receipted(awaitRelease: Boolean?, deliver: (done: () -> Unit) -> Unit): Released? {
        val released = CompletableDeferred<AtRelease>()
        val mark = TimeSource.Monotonic.markNow()
        // `complete` is idempotent, so a second release is tolerated rather than thrown inside an OS handler lambda.
        withContext(lane) { deliver { released.complete(atRelease()) } }
        if (awaitRelease != true) return null
        val held = released.await()
        return Released(mark.elapsedNow().inWholeMilliseconds, held)
    }
}
