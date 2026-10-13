package app.snapsync.ports

import app.snapsync.inbound.Background
import app.snapsync.inbound.CompositionOnly
import app.snapsync.inbound.Inbound

/**
 * **The platform's background downloads**: on iOS a background `URLSession`, which
 * keeps downloading while the app is suspended and relaunches it when a transfer ends. One external system, deciding
 * nothing: whether a finished body may be staged, where it goes and what it means are the download feature's.
 *
 * An event port. What the operating system reports arrives through the [DownloadHandlers] a composition registers
 * once — including for transfers a **previous process** started, which a relaunched process is handed with no memory
 * of them; the [start]ing tag is how each is attributed.
 *
 * **It offers no way to destroy its session.** Creating a task on an invalidated `NSURLSession` raises an
 * Objective-C exception Kotlin/Native cannot catch, so a session destroyed as a means of cancelling would be a crash
 * waiting for the next reconcile. A session the *system* invalidates is rebuilt by the adapter on the next [start];
 * each transfer it still held reports [DownloadHandlers.onCompleted], with an error.
 */
interface Download : EventPort<DownloadHandlers> {

    /**
     * Begin fetching [url] with [headers] — the version the app declares on every request; the redirect it fetches needs
     * no token — tagging the transfer with [tag] — the one field the platform keeps across a relaunch — over
     * the networks [network] allows: under [TransferNetwork.UNRESTRICTED_ONLY] the platform
     * holds the transfer until the phone is on a network that is neither costly nor data-restricted, keeping it across
     * a relaunch like any other. Never throws: an unusable [url] is the caller's to filter (it does), and anything else
     * is [StartResult.NotStarted], which converges with a started-then-failed transfer (the resource stays pending until
     * the next reconcile).
     */
    fun start(url: String, headers: Map<String, String>, tag: String, network: TransferNetwork): StartResult

    /**
     * Cancel **every** transfer the session holds when called — this process's and any a relaunched process inherited —
     * and return once each is cancelled. A transfer started after it returns is not affected. Each cancelled transfer
     * still reports [DownloadHandlers.onCompleted], with an error.
     */
    suspend fun cancelAll()
}

/** What the platform tells the core about its downloads. Built only by a composition. */
class DownloadHandlers @CompositionOnly constructor(
    /**
     * A body finished arriving, at a file the platform deletes when this returns. **Inline**: whatever keeps the
     * bytes (a move to staging) happens before returning; a store write it causes may be launched, and is joined by
     * the relaunch's run before it answers.
     */
    val onFinished: Inbound<FinishedDownload, Unit>,
    /** A transfer ended — finished, failed or cancelled. Its slot is free. */
    val onCompleted: Inbound<CompletedDownload, Unit>,
    /**
     * The operating system relaunched (or woke) the app to deliver this session's events, handing the completion the
     * relaunch's run answers once the events are drained and what they staged is recorded — or once its time is up.
     */
    val onBackgroundEvents: Background<SessionRelaunch>,
    /** The session delivered every event it held (`urlSessionDidFinishEvents`). */
    val onEventsDrained: Inbound<SessionDrain, Unit>,
)

/** [tag]'s body finished arriving, with the [facts] to judge it by, at [tempPath]. */
class FinishedDownload(val tag: String, val facts: TransferOutcome, val tempPath: String)

/** [tag]'s transfer ended; [error] describes a failure, `null` for none. */
class CompletedDownload(val tag: String, val error: String?)
