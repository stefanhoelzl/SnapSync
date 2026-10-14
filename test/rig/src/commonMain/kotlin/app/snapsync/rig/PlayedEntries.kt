package app.snapsync.rig

import app.snapsync.contracts.EntryDriver
import app.snapsync.levers.OsLevers
import app.snapsync.levers.Released
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.time.TimeSource

/**
 * The `os` service ([OsLevers]) over a host's [driver] (`docs/testing.md`, "Typed services"): each delivery the one
 * [EntryDriver] delivery the `/os/app` verb of the same name makes, on [lane] — the lane the platform delivers on, as
 * Swift calls the app root's entry points on main — so a typed delivery is the same call the string verb makes.
 *
 * A delivery the operating system hands a completion handler answers once delivered, or — `awaitRelease` `true`, for a
 * host with no event stream — once the app released the handler, with how long it held it.
 */
class PlayedEntries(private val driver: EntryDriver, private val lane: CoroutineContext) : OsLevers {
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
        val released = CompletableDeferred<Unit>()
        val mark = TimeSource.Monotonic.markNow()
        withContext(lane) { deliver { released.complete(Unit) } }
        if (awaitRelease != true) return null
        released.await()
        return Released(mark.elapsedNow().inWholeMilliseconds)
    }
}
