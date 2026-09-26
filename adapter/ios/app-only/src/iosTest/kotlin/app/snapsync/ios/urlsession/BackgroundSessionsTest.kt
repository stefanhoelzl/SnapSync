package app.snapsync.ios.urlsession

import app.snapsync.download.DOWNLOAD_SESSION_ID
import app.snapsync.download.IosDownload
import app.snapsync.ports.DownloadHandlers
import app.snapsync.ports.UploadHandlers
import co.touchlab.kermit.Logger
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Where `handleEventsForBackgroundURLSession` goes (capability `sync-status`): the app uploader's session by its
 * identifier, and every other identifier to the download session — so a session this build does not know is handed to
 * one that can report its drain, never dropped with its handler held. Was `PlatformEntriesContract`'s
 * `OTHER_TRANSFERS_ARE_ADOPTED_BY_THE_DOWNLOADS` while the relaunch crossed the inbound port (phase 11f).
 */
class BackgroundSessionsTest {

    @Test
    fun `each session's events reach the handlers of the session the operating system named`() {
        val handedTo = mutableListOf<String>()
        val upload = IosUrlSessionUploadPlatform(Logger.withTag("test"), "app.snapsync.test.upload")
        upload.listen(UploadHandlers(onFinished = {}, onBackgroundEvents = { handedTo += "upload" }, onEventsDrained = {}))
        val download = IosDownload(Logger.withTag("test"))
        download.listen(
            DownloadHandlers(
                onFinished = { _, _, _ -> },
                onCompleted = { _, _ -> },
                onInvalidated = {},
                onBackgroundEvents = { handedTo += "download" },
                onEventsDrained = {},
            ),
        )
        val sessions = BackgroundSessions(upload, download)

        sessions.handleEvents(UPLOAD_SESSION_ID) {}
        sessions.handleEvents(DOWNLOAD_SESSION_ID) {}
        sessions.handleEvents("app.example.unknown-session") {}

        assertEquals(listOf("upload", "download", "download"), handedTo)
    }
}
