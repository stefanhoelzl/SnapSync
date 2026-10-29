package app.snapsync.integration

import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.Given
import app.snapsync.control.behaviour.behaviour
import app.snapsync.levers.TransferEnd
import app.snapsync.levers.WorldEvent
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes

/**
 * The operating system's completion handlers and its expiry, as it plays them (decision record `own-work-per-wake`):
 * the heartbeat task, the transfer sessions' relaunches and the silent push, each over the control protocol, with the
 * OS's expiry delivered by `played-os`'s `expire`.
 *
 * "Released after the work" is a statement about an order, so it is judged on the events: the operating system's record
 * of the release ([WorldEvent.CompletionReleased]) against the work's own outcome. The work a tail does here is the one
 * unit a JVM host can see land — the import of a staged photo into the library — so most tests start from a photo whose
 * first import failed and whose bytes wait staged.
 *
 * Which of a wake's units ran, and that none starts after a stop, is the units' own tests (`:test:feature`): the JVM
 * root's uploader is the operator, so its top-up and walk move nothing a protocol could read.
 */
class OsCompletionIntegrationTest {

    /**
     * A joined device, relaunched in the background, holding a received photo whose import failed once: its bytes wait in
     * the staging directory. Answers the event, and the storage the app took before the bytes arrived.
     */
    private suspend fun Given.stagedBacklog(): Backlog {
        val event = joinedEvent()
        val before = downloadStarted()
        step(
            "its first import failed, its bytes kept for the retry",
            act = {
                library.failNextImport()
                downloadSession.finish()
            },
            until = {
                event<WorldEvent.DownloadEnded> { it.outcome.how == TransferEnd.How.DELIVERED }
                never<WorldEvent.LibraryAssetCreated>() // the retry has not run yet
                settled { true }
            },
        )
        backgroundLaunch()
        return Backlog(event, checkNotNull(before.state.appStorageBytes))
    }

    private class Backlog(val event: String, val storage: Long)

    /** The backlog's photo, imported — under the library's next id, the failed attempt's spent. */
    private fun WorldEvent.LibraryAssetCreated.isTheBacklog() = asset.startsWith(imported())

    // ---- the heartbeat task --------------------------------------------------------------------------------------

    @Test
    @Verifies(
        spec = "delivery",
        requirement = "Photos travel without the app being opened",
        scenario = "A save that ran out of time completes later",
    )
    fun a_heartbeat_wake_runs_the_tail_then_completes_once_and_re_arms() = behaviour {
        val backlog = given { stagedBacklog() }

        whenever { heartbeat() }
        then {
            // released after the tail — the wake's work — imported the backlog
            inOrder {
                event<WorldEvent.LibraryAssetCreated> { it.isTheBacklog() }
                event<WorldEvent.CompletionReleased> { !it.again }
            }
            times(1) { event<WorldEvent.WakeScheduled> { it.wake.wake == "heartbeat" } }
            never<WorldEvent.CompletionReleased> { it.again } // the completion is released exactly once
            settled { checkNotNull(it.state.appStorageBytes) <= backlog.storage } // and the staged bytes with it
        }
    }

    @Test
    fun the_operating_systems_expiry_completes_the_wake_at_once_and_a_cut_tail_still_re_arms() = behaviour {
        given {
            stagedBacklog()
            step("the next import parks in the library", act = { library.suspendNextImport() }, until = {})
        }

        whenever { heartbeat() }
        then {
            event<WorldEvent.LibraryImportParked>() // the tail's import is in flight
            never<WorldEvent.CompletionReleased>()
        }

        // Released on the operating system's signal — while the import is still parked, not after the work.
        whenever { playedOs.expire() }
        then {
            event<WorldEvent.CompletionReleased> { !it.again }
            never<WorldEvent.LibraryAssetCreated>() // the unit in flight had not finished when the handler was released
            event<WorldEvent.WakeScheduled> { it.wake.wake == "heartbeat" }
        }

        whenever { library.resumeImport() }
        then {
            never<WorldEvent.CompletionReleased>() // the release after the work does not release it again
        }
    }

    @Test
    fun an_expiry_after_the_wake_ended_releases_nothing() = behaviour {
        given {
            joinedEvent()
            step("a heartbeat wake ran and ended", act = { heartbeat() }, until = {
                event<WorldEvent.CompletionReleased> { !it.again }
                settled { true }
            })
        }

        whenever { playedOs.expire() }
        then { never<WorldEvent.CompletionReleased>() }
    }

    @Test
    fun a_wake_whose_time_was_up_before_it_ran_is_released_and_runs_no_tail() = behaviour {
        given { stagedBacklog() }

        // an expiry registered late runs at once
        whenever {
            playedOs.expireNext()
            heartbeat()
        }
        then {
            event<WorldEvent.CompletionReleased> { !it.again }
            never<WorldEvent.LibraryAssetCreated>() // no tail is requested with no time left to run in
        }
    }

    // ---- the transfer sessions' relaunches ------------------------------------------------------------------------

    @Test
    @Verifies(
        spec = "delivery",
        requirement = "Photos travel without the app being opened",
        scenario = "A save that ran out of time completes later",
    )
    fun upload_session_events_release_at_the_drain_then_run_the_tail() = behaviour {
        val backlog = given { stagedBacklog() }

        // The upload session is handed its events, and reports them drained at once: only its drain releases the handler.
        whenever { os.backgroundTransfers(UPLOAD_SESSION) }
        then {
            // released at the session's drain report, before the tail
            inOrder {
                event<WorldEvent.CompletionReleased> { !it.again }
                event<WorldEvent.LibraryAssetCreated> { it.isTheBacklog() }
            }
            event<WorldEvent.HoldEnded>()
            settled { checkNotNull(it.state.appStorageBytes) <= backlog.storage }
        }
    }

    @Test
    fun download_session_events_bring_the_download_session_up_and_not_the_upload_one() = behaviour {
        given {
            joinedEvent()
            downloadStarted()
            backgroundLaunch()
        }

        // The download session, brought up by its events, delivers the transfer the dead process started; the upload
        // session is not handed them, or its drain report would release the handler.
        whenever {
            os.backgroundTransfers(DOWNLOAD_SESSION)
            downloadSession.finish()
        }
        then {
            event<WorldEvent.DownloadEnded> { it.outcome.how == TransferEnd.How.DELIVERED }
            never<WorldEvent.CompletionReleased>()
        }

        whenever { playedOs.expire() } // this session never reports its events drained
        then { event<WorldEvent.CompletionReleased> { !it.again } }
    }

    @Test
    fun a_drain_report_that_never_comes_releases_on_the_operating_systems_expiry() = behaviour {
        given {
            joinedEvent()
            backgroundLaunch()
        }

        whenever {
            os.backgroundTransfers(DOWNLOAD_SESSION)
            advance(1.minutes)
        }
        then { never<WorldEvent.CompletionReleased>() } // no clock of the app's own releases it

        whenever { playedOs.expire() }
        then {
            // released exactly once, on the operating system's expiry
            times(1) { event<WorldEvent.CompletionReleased> { !it.again } }
            never<WorldEvent.CompletionReleased> { it.again }
            event<WorldEvent.HoldEnded>() // and the wake's hold ended with it
        }
    }

    // ---- the silent push's tail -----------------------------------------------------------------------------------

    @Test
    @Verifies(spec = "app-experience", requirement = "Background wakes do their work without showing anything")
    fun a_push_for_the_active_event_re_arms_the_heartbeat() = behaviour {
        val event = given { joinedEvent().also { backgroundLaunch() } }
        whenever { os.silentPush(event) }
        then {
            event<WorldEvent.WakeScheduled> { it.wake.wake == "heartbeat" }
            // A background wake assembles no host.
            never<WorldEvent.UiChanged>()
        }
    }

    @Test
    @Verifies(
        spec = "delivery",
        requirement = "Photos travel without the app being opened",
        scenario = "A save that ran out of time completes later",
    )
    fun a_push_whose_union_read_fails_still_imports_what_is_staged() = behaviour {
        val backlog = given { stagedBacklog() }

        whenever {
            backend.offline(true) // the push's union read fails fast
            os.silentPush(backlog.event)
        }
        then {
            event<WorldEvent.CompletionReleased> { !it.again }
            event<WorldEvent.LibraryAssetCreated> { it.isTheBacklog() }
            settled { checkNotNull(it.state.appStorageBytes) <= backlog.storage }
        }
    }
}
