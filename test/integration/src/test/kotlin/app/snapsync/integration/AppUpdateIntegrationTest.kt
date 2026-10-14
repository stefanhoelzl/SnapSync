package app.snapsync.integration

import app.snapsync.control.Choices
import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.Acts
import app.snapsync.control.behaviour.Behaviour
import app.snapsync.control.behaviour.Expectations
import app.snapsync.control.behaviour.Given
import app.snapsync.control.behaviour.Seen
import app.snapsync.control.behaviour.behaviour
import app.snapsync.levers.BackendFault
import app.snapsync.levers.ExtensionAnswer
import app.snapsync.levers.SeededInstall
import app.snapsync.levers.WorldEvent
import app.snapsync.ui.state.DirectionCount
import app.snapsync.ui.state.JoinPhase
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.UiIntent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * **An update keeps the event** — whatever an older install left on the device. Each case is a device in the middle of
 * an event — joined saving to the album, two own photos shared and one on its way, two of a fellow member's photos
 * received and one on its way, an own photo waiting for the upload extension, and a leave of an earlier event the
 * backend has not heard yet — whose databases an older build left: `older-install` rewrites what the device holds as
 * that build held it, and the relaunch after it is the update.
 *
 * Every version the migration chain still builds is a case, the newest first, until the service answers that none is
 * older — so a migration is covered the day it is added, and a squash of the chain retires its cases. The shims these
 * reach (`PENDING_CLEANUPS.md`) are the oldest versions' layers: the shared records' legacy homes and the transfers 0.4
 * started.
 *
 * After the update: the joined screen shows the same event with the same settings; the photos that left the device do
 * not leave again, the ones it received are not fetched again, and the album is the same one; the transfers in flight
 * land once; the pending leave reaches the backend; and the upload extension, which waits while the app has not yet
 * brought the device's stores up to this build, shares the waiting photo once the app has.
 */
class AppUpdateIntegrationTest {

    @Test
    @Verifies(
        spec = "manage-membership",
        requirement = "A membership survives updates, restarts, and a locked phone",
        scenario = "An app update keeps the event",
    )
    @Verifies(
        spec = "delivery",
        requirement = "A photo is never lost on the way",
        scenario = "The app is killed mid-upload",
    )
    fun an_update_from_every_older_ledger_keeps_the_event_and_shares_nothing_twice() =
        everyOlder(SeededInstall::olderLedgers, waits = SeededInstall::legacyRecords) { behind ->
            olderInstall.installedAt(ledgerBehind = behind)
        }

    @Test
    @Verifies(
        spec = "manage-membership",
        requirement = "A membership survives updates, restarts, and a locked phone",
        scenario = "An app update keeps the event",
    )
    fun an_update_from_every_older_download_store_keeps_the_event_and_receives_nothing_twice() =
        everyOlder(SeededInstall::olderDownloads, waits = { true }) { behind ->
            olderInstall.installedAt(downloadsBehind = behind)
        }

    /**
     * One update per version [older] counts down to, the newest first. [waits] is whether the upload extension waits for
     * the app on such an install: while the shared records are in their legacy homes, or `downloads.db` is older than
     * this build's — neither is the extension's to bring up to date.
     */
    private fun everyOlder(
        older: (SeededInstall) -> Int,
        waits: (SeededInstall) -> Boolean,
        install: suspend Acts.(behind: Int) -> SeededInstall,
    ) {
        var behind = 1
        do {
            val seeded = updateFrom(waits) { install(behind) }
        } while (older(seeded).also { behind++ } > 0)
    }

    /** The device in the middle of the event, as [install] leaves it, then updated. Answers what [install] answered. */
    private fun updateFrom(
        waits: (SeededInstall) -> Boolean,
        install: suspend Acts.() -> SeededInstall,
    ): SeededInstall {
        lateinit var seeded: SeededInstall
        behaviour {
            val mid = given { midEvent() }
            seeded = given {
                step("the backend hears leaves again", act = { backend.recover("leaveEvent") }, until = {})
                step(
                    "the device as the older install left it, updated",
                    act = { install().also { host.relaunch(scene = false) } },
                    until = { settled { true } },
                )
            }
            updated(mid, paused = waits(seeded))
        }
        return seeded
    }

    /** What the device showed in the middle of the event: its joined screen, and the album it kept. */
    private class MidEvent(val joined: Layer.Joined, val album: String)

    /**
     * The device in the middle of the event, as this build leaves it: joined saving to the album, an earlier event's leave
     * unheard, own photos shared and one on its way, a fellow member's received and one on its way, and an own photo
     * waiting for the upload extension.
     */
    private suspend fun Given.midEvent(): MidEvent {
        lateinit var joined: Seen<Layer.Joined>
        lateinit var album: Seen<WorldEvent.LibraryAlbumCreated>
        val received = mutableListOf<Seen<WorldEvent.DownloadStarted>>()
        step(
            "events created plain, as every release before this build's creates them",
            act = { devControls.encryptNewEvents(false) },
            until = {},
        )
        val event = registeredEvent(name = EVENT)
        joinedEvent()
        step(
            "its leave never reaching the backend",
            act = { backend.fail("leaveEvent", BackendFault.SERVER_ERROR, times = UNTIL_RECOVERED) },
            until = {},
        )
        step("left, the leave pending", act = { tap(UiIntent.LeaveEvent) }, until = {
            screen<Layer.CreateEvent>()
            settled { true }
        })
        step("the event's invite opened", act = { openLink(inviteLink(event)) }, until = {
            screen<Layer.JoiningEvent> { it.phase is JoinPhase.Detailed }
        })
        step("joined, saving to its album", act = { confirmJoin(Choices(saveToAlbum = true)) }, until = {
            joined = screen<Layer.Joined> { it.membership.eventId == event }
            album = event<WorldEvent.LibraryAlbumCreated>()
            settled { true }
        })
        pushToken(PUSH_TOKEN)
        step("own photos taken, and the app opened", act = {
            SHARED.forEach { takePhoto(it) }
            os.foreground()
        }, until = {
            SHARED.forEach { photo -> event<WorldEvent.SessionTransferCreated> { it.key == primaryKey(photo) } }
            settled { true }
        })
        step(
            "all but one of them shared",
            act = { SHARED.dropLast(1).forEach { uploadSession.complete(primaryKey(it)) } },
            until = { settled { true } },
        )
        step(
            "a fellow member's photos on their way",
            act = {
                publicBackend.foreignDevice(DEV_F, RECEIVED)
                os.foreground()
            },
            until = {
                RECEIVED.forEach { photo -> received += event<WorldEvent.DownloadStarted> { it.names(photo) } }
                settled { true }
            },
        )
        step(
            "all but one of them received into the album",
            act = { received.dropLast(1).forEach { downloadSession.finish(it.value.description) } },
            until = {
                RECEIVED.dropLast(1).forEach { photo ->
                    event<WorldEvent.LibraryJoinedAlbum> { it.album == album.value.album && photo in it.asset }
                }
                settled { true }
            },
        )
        extensionUploadsOnly()
        photos(WAITING)
        return MidEvent(joined.value, album.value.album)
    }

    /** What the update leads to, from the upload extension's first invocation on — [paused] while it waits for the app. */
    private suspend fun Behaviour.updated(mid: MidEvent, paused: Boolean) {
        lateinit var beforeTheApp: ExtensionAnswer
        whenever { beforeTheApp = extensionHost.process() }
        then {
            nothingTwice()
            if (paused) {
                never<WorldEvent.UploadJobCreated>()
            } else {
                event<WorldEvent.UploadJobCreated> {
                    it.job == primaryJob(WAITING)
                }
            }
        }
        // Waiting or not, it asks to be invoked again: a waiting extension that told the system it is done would leave
        // the photo for the next time the system happens to invoke it.
        assertEquals(ExtensionAnswer.PROCESSING, beforeTheApp, "what the extension tells the system before the app ran")

        // The transfers in flight across the update finish while the app is not running, and the operating system
        // relaunches it in the background for their events: each lands once, under what the older build started it
        // as, the received photo joins the album the device had, and the leave the device owed reaches the backend.
        whenever {
            uploadSession.complete()
            os.backgroundTransfers(UPLOAD_SESSION)
            os.backgroundTransfers(DOWNLOAD_SESSION)
            downloadSession.finish()
            downloadSession.reportEventsDrained()
        }
        then {
            nothingTwice()
            event<WorldEvent.LibraryJoinedAlbum> { it.album == mid.album && RECEIVED.last() in it.asset }
            event<WorldEvent.BackendReceived> { it.operation == "leaveEvent" }
        }

        // The app opens: the same event, the same settings, and every photo that travelled counted once.
        whenever {
            os.foreground()
            advance(COUNTS_POLL)
        }
        then {
            nothingTwice()
            screen<Layer.Joined> { it.membership == mid.joined.membership && it.shared == 3 && it.received == 3 }
        }

        // The extension shares the waiting photo — now, if it waited for the app.
        whenever { extensionHost.process() }
        then {
            nothingTwice()
            if (paused) {
                event<WorldEvent.UploadJobCreated> { it.job == primaryJob(WAITING) }
            } else {
                never<WorldEvent.UploadJobCreated>()
            }
        }

        whenever {
            uploadQueue.completeJob(primaryJob(WAITING))
            extensionHost.process()
            advance(COUNTS_POLL)
        }
        then {
            nothingTwice()
            screen<Layer.Joined> { it.shared == 4 }
        }
    }

    /** How many own photos the joined screen counts as shared, or `null` while it shows no count. */
    private val Layer.Joined.shared: Int? get() = (counts?.shared as? DirectionCount.Progress)?.done

    /** How many of the others' photos the joined screen counts as received, or `null` while it shows no count. */
    private val Layer.Joined.received: Int? get() = (counts?.received as? DirectionCount.Progress)?.done

    /**
     * Nothing that left the device leaves again, nothing it received is fetched again — the transfers in flight across
     * the update included — and its album is the one it had.
     */
    private fun Expectations.nothingTwice() {
        never<WorldEvent.SessionTransferCreated>()
        never<WorldEvent.UploadJobCreated> { job -> SHARED.any { job.job == primaryJob(it) } }
        never<WorldEvent.DownloadStarted> { started -> RECEIVED.any { started.names(it) } }
        never<WorldEvent.LibraryAlbumCreated>()
    }

    /** Whether this download's description names [photo] as its source asset. */
    private fun WorldEvent.DownloadStarted.names(photo: String) = description.split("\n").getOrNull(1) == photo

    private companion object {
        const val EVENT = "Lena's Graduation"
        const val PUSH_TOKEN = "apns-token-of-the-older-install"

        /** Own photos taken in the event: every one but the last shared before the update, the last on its way. */
        val SHARED = listOf("P1", "P2", "P3")

        /** An own photo taken while only the upload extension shares: it waits for the extension. */
        const val WAITING = "P4"

        /** A fellow member's photos: every one but the last received before the update, the last on its way. */
        val RECEIVED = listOf("F1", "F2", "F3")

        /** Past the joined screen's next read of the counts. */
        val COUNTS_POLL = 5.seconds

        /** Enough refusals that the leave is never heard until the faults are lifted. */
        const val UNTIL_RECOVERED = 1_000
    }
}
