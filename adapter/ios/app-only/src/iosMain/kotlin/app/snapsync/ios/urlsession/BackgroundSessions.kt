package app.snapsync.ios.urlsession

import app.snapsync.download.IosDownload
import co.touchlab.kermit.Logger

/** The app uploader's background-session identifier — stable, so an app relaunch reconnects to its transfers. */
const val UPLOAD_SESSION_ID = "app.snapsync.upload.session"

/**
 * Where `handleEventsForBackgroundURLSession` goes (capability `sync-status`): to the session the operating system
 * named — the app's [upload] session by its identifier, and **every other identifier to the [download] session**, the
 * one other session this app creates. An identifier this build does not know is thereby handed to a session that can
 * bring itself up and report its drain, rather than dropped with its handler held (decision record
 * `changes/own-work-per-wake`).
 *
 * It routes by the identifier alone — the platform's own vocabulary, which is why it is an adapter's — and decides
 * nothing about the wake: each session hands its handler to the handlers the composition registered.
 */
class BackgroundSessions(
    private val upload: IosUrlSessionUploadPlatform,
    private val download: IosDownload,
    private val log: Logger = Logger.withTag("BackgroundSessions"),
) {
    /** The operating system relaunched (or woke) the app for the session [identifier], handing [completion]. */
    fun handleEvents(identifier: String, completion: () -> Unit) {
        log.i { "background session events for '$identifier'" }
        if (identifier == UPLOAD_SESSION_ID) upload.handleEvents(completion) else download.handleEvents(completion)
    }
}
