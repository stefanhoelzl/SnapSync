package app.snapsync.contracts

import app.snapsync.ports.PlatformEntries
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The states the app a [PlatformEntries] drives can be found in, as far as a clause cares.
 */
enum class PlatformEntriesState {
    /** Not joined; an event exists on the backend that [PlatformEntriesObservations.inviteUrl] invites to. */
    UNJOINED,

    /**
     * Joined to [PlatformEntriesObservations.joinedEventId], whose backend holds a photo another device took and
     * this device has not downloaded yet, with full photo access. Nothing has run: no download is planned, no tail
     * has run, and no host is assembled — the process is as a cold background start finds it.
     */
    JOINED_WITH_FOREIGN_PHOTO,
}

/**
 * What a clause reads to see an entry's **outcome** in the app behind the port (capability `port-contracts`: an
 * inbound port declares no reads, so a binding supplies these over the system it built). Every read is a snapshot of
 * that system's state — never a record of which collaborator the implementation called, because a call transcript
 * restates the wiring and is passed by anything that mirrors it.
 *
 * Two members are the **operating system's levers** rather than reads, because an inbound port's promises about
 * Apple's expiry and about a unit in flight cannot be exercised otherwise: [expireBackgroundTime] is what the
 * operating system does when background time is up, and [parkNextUploadUnit] holds the app uploader's next unit in
 * flight, as a slow photo-library or network call would.
 */
interface PlatformEntriesObservations {
    /** The identifiers the operating system delivers, as the implementation under test was configured with them. */
    val identifiers: EntryIdentifiers

    /** A valid invite to the event an [PlatformEntriesState.UNJOINED] app can join. */
    val inviteUrl: String

    /** The event [inviteUrl] names. */
    val invitedEventId: String

    /** The event a [PlatformEntriesState.JOINED_WITH_FOREIGN_PHOTO] app is joined to. */
    val joinedEventId: String

    /** The event the join gate currently shows, or `null` when no gate is open. */
    fun joinGateEventId(): String?

    /** The transient error the screen currently shows, or `null`. */
    fun transientError(): String?

    /** Whether the app has recorded that it became active. */
    fun becameActive(): Boolean

    /** How many foreign photos are planned for download. */
    fun plannedForeignDownloads(): Int

    /** How many times the app uploader's heartbeat has been scheduled with the operating system. */
    fun heartbeatsScheduled(): Int

    /** The push tokens that reached the registration source, in order. */
    fun deliveredPushTokens(): List<String>

    /** How many upload top-ups (the tail's ②) the app's uploader has run. */
    fun appUploaderTopUps(): Int

    /** How many walks → manifest publishes (the tail's ③) the app's uploader has run. */
    fun appUploaderWalks(): Int

    /** How many background-transfer handbacks reached the app uploader's session. */
    fun appUploaderTransferHandbacks(): Int

    /** Whether the download session has been brought up to receive handed-back transfers. */
    fun downloadSessionRealized(): Boolean

    /** How many holds on the process's background time are outstanding. */
    fun backgroundTimeHolds(): Int

    /** The operating system's lever: every outstanding background-time hold's time is up. */
    fun expireBackgroundTime()

    /** The operating system's lever: hold the app uploader's next unit in flight; the answer lets it finish. */
    fun parkNextUploadUnit(): () -> Unit
}

/** The operating system's identifiers for the entries that route by one. */
class EntryIdentifiers(
    val uploadHeartbeatTask: String,
    val uploadTransferChannel: String,
    /** Any transfer channel that is not [uploadTransferChannel] — the downloads'. */
    val downloadTransferChannel: String,
)

/** What a clause is handed: the port under contract and the handle it observes outcomes through. */
class PlatformEntriesSubject(val entries: PlatformEntries, val observe: PlatformEntriesObservations)

/**
 * What the app process's inbound port promises (capability `port-contracts` — this list IS the specification of
 * the port's obligations; spec `module-architecture`, "OS entry points cross an inbound port").
 *
 * Each clause fires one operating-system entry and asserts what happened in the app behind it. That is what makes
 * a crossed wire visible: an entry routed to the wrong flow produces the wrong outcome, whichever names the code
 * used. An entry that takes a completion is held to **"released exactly once, after the wake's own work, and before
 * the tail"** — the completion is part of the port's own signature (capability `ios-app-shell`, "OS completion
 * handlers are released only after their work completes"; decision record `changes/own-work-per-wake`) — and, where
 * the operating system's expiry arrives, to **"released at once, the tail stopped, the background time ended"**.
 *
 * **One implementation, no double.** The core's `platformEntries` is the only implementation; its bindings run it
 * on the JVM and in the simulator's test executable over the world. No `Fake` binding exists because nothing
 * licenses a double of this port: the contract's worth is as the specification the next driver of the core — an
 * integration bundle's host, an Android shell — must pass.
 *
 * The app behind the port runs in **real time** (the world's mini-edge serves on a real dispatcher), so waits are
 * bounded real-time polls rather than the clause's virtual clock.
 */
object PlatformEntriesContract : Contract<PlatformEntriesState, PlatformEntriesSubject>("PlatformEntries") {

    override val clauses = clauses {

        clause("OPEN_URL_OPENS_THE_JOIN_GATE", PlatformEntriesState.UNJOINED) { (entries, observe) ->
            entries.onOpenUrl(observe.inviteUrl)
            assertTrue(eventually { observe.joinGateEventId() == observe.invitedEventId }, "the gate opens on the event")
        }

        clause("OPEN_URL_OF_A_BAD_LINK_SHOWS_THE_ERROR", PlatformEntriesState.UNJOINED) { (entries, observe) ->
            entries.onOpenUrl("https://example.invalid/not-an-invite")
            assertTrue(eventually { observe.transientError() != null }, "a link that decodes to nothing says so")
            assertNull(observe.joinGateEventId(), "and opens no gate")
        }

        clause("FOREGROUND_RECONCILES_THEN_RUNS_THE_TAIL", PlatformEntriesState.JOINED_WITH_FOREIGN_PHOTO) {
            (entries, observe) ->
            entries.onForeground()
            assertTrue(eventually { observe.becameActive() }, "the app records that it became active")
            assertTrue(eventually { observe.plannedForeignDownloads() > 0 }, "the foreground reconcile plans the photo")
            assertTrue(eventually { observe.appUploaderTopUps() > 0 }, "and the tail tops up after the own work")
            assertTrue(eventually { observe.heartbeatsScheduled() > 0 }, "foreground entry re-arms the heartbeat")
            assertTrue(eventually { observe.backgroundTimeHolds() == 0 }, "its hold ends with its tail")
        }

        clause("BACKGROUND_ARMS_NOTHING", PlatformEntriesState.JOINED_WITH_FOREIGN_PHOTO) { (entries, observe) ->
            entries.onBackground()
            settle()
            assertEquals(0, observe.heartbeatsScheduled(), "leaving the foreground queues no background task")
            assertTrue(!observe.becameActive(), "and is not an activation")
        }

        clause("PUSH_TOKEN_REACHES_REGISTRATION", PlatformEntriesState.UNJOINED) { (entries, observe) ->
            entries.onPushToken(TOKEN)
            assertEquals(listOf(TOKEN), observe.deliveredPushTokens())
        }

        clause("SILENT_PUSH_RELEASES_AFTER_ITS_OWN_WORK_THEN_RUNS_THE_TAIL", PlatformEntriesState.JOINED_WITH_FOREIGN_PHOTO) {
            (entries, observe) ->
            val completion = Completion { observe.plannedForeignDownloads() > 0 && observe.appUploaderTopUps() == 0 }
            entries.onSilentPush(mapOf<Any?, Any?>("eventId" to observe.joinedEventId), completion::release)
            completion.assertReleasedOnceAfterTheWork()
            assertTrue(eventually { observe.appUploaderTopUps() > 0 }, "the tail runs after the release")
            assertTrue(eventually { observe.backgroundTimeHolds() == 0 }, "and the push's hold ends with it")
        }

        clause("SILENT_PUSH_FOR_ANOTHER_EVENT_JOINS_NO_TAIL", PlatformEntriesState.JOINED_WITH_FOREIGN_PHOTO) {
            (entries, observe) ->
            val completion = Completion { true }
            entries.onSilentPush(mapOf<Any?, Any?>("eventId" to "not-the-joined-event"), completion::release)
            completion.assertReleasedOnceAfterTheWork()
            assertEquals(0, observe.plannedForeignDownloads(), "the download arm's guard refuses it")
            assertEquals(0, observe.appUploaderTopUps(), "and no tail runs for it")
            assertTrue(eventually { observe.backgroundTimeHolds() == 0 }, "its hold ends at once")
        }

        clause("SILENT_PUSH_WITHOUT_AN_EVENT_STILL_RELEASES", PlatformEntriesState.JOINED_WITH_FOREIGN_PHOTO) {
            (entries, observe) ->
            val completion = Completion { true }
            entries.onSilentPush(emptyMap<Any?, Any?>(), completion::release)
            completion.assertReleasedOnceAfterTheWork()
            assertEquals(0, observe.plannedForeignDownloads(), "a push naming no event reaches no arm")
            assertEquals(0, observe.appUploaderTopUps(), "and joins no tail")
        }

        clause("BACKGROUND_TIME_EXPIRY_STOPS_THE_TAIL_AND_ENDS_THE_HOLD_AT_ONCE", PlatformEntriesState.JOINED_WITH_FOREIGN_PHOTO) {
            (entries, observe) ->
            val finishUnit = observe.parkNextUploadUnit()
            val completion = Completion { observe.plannedForeignDownloads() > 0 }
            entries.onSilentPush(mapOf<Any?, Any?>("eventId" to observe.joinedEventId), completion::release)
            completion.assertReleasedOnceAfterTheWork()
            assertTrue(eventually { observe.appUploaderTopUps() == 1 }, "the tail's top-up is in flight")
            observe.expireBackgroundTime()
            // At once — while the unit is still in flight: Apple's recipe, never the watchdog's.
            assertTrue(eventually { observe.backgroundTimeHolds() == 0 }, "the hold is ended without awaiting the unit")
            finishUnit()
            settle()
            assertEquals(0, observe.appUploaderWalks(), "the unit in flight completed, and no further unit started")
        }

        clause("HEARTBEAT_TASK_RUNS_THE_TAIL_THEN_COMPLETES", PlatformEntriesState.JOINED_WITH_FOREIGN_PHOTO) {
            (entries, observe) ->
            val completion = Completion { observe.appUploaderTopUps() > 0 && observe.appUploaderWalks() > 0 }
            entries.onBackgroundTask(observe.identifiers.uploadHeartbeatTask, completion::release)
            completion.assertReleasedOnceAfterTheWork()
            assertEquals(1, observe.heartbeatsScheduled(), "the heartbeat re-submits itself after its tail")
        }

        clause("UNKNOWN_TASK_IS_RELEASED_WITHOUT_WORK", PlatformEntriesState.JOINED_WITH_FOREIGN_PHOTO) {
            (entries, observe) ->
            val completion = Completion { true }
            entries.onBackgroundTask("app.example.not-registered", completion::release)
            completion.assertReleasedOnceAfterTheWork()
            assertEquals(0, observe.appUploaderTopUps(), "no tail")
            assertEquals(0, observe.heartbeatsScheduled(), "and nothing re-armed")
        }

        clause("TIME_UP_COMPLETES_THE_RUNNING_TASK_AT_ONCE", PlatformEntriesState.JOINED_WITH_FOREIGN_PHOTO) {
            (entries, observe) ->
            val finishUnit = observe.parkNextUploadUnit()
            val completion = Completion { observe.appUploaderTopUps() == 1 }
            entries.onBackgroundTask(observe.identifiers.uploadHeartbeatTask, completion::release)
            assertTrue(eventually { observe.appUploaderTopUps() == 1 }, "the heartbeat's tail is in flight")
            entries.onBackgroundTaskTimeUp(observe.identifiers.uploadHeartbeatTask)
            // Completed while the unit is still in flight, exactly once — the shell completing it in its own expiration
            // handler and the core completing it again after the work is the double completion this entry replaces.
            completion.assertReleasedOnce()
            finishUnit()
            settle()
            assertEquals(0, observe.appUploaderWalks(), "no unit starts after the stop")
            assertTrue(eventually { observe.heartbeatsScheduled() == 1 }, "a tail cut short still re-arms")
            completion.assertReleasedOnce()
        }

        clause("TIME_UP_AFTER_THE_TASK_ENDED_RELEASES_NOTHING", PlatformEntriesState.JOINED_WITH_FOREIGN_PHOTO) {
            (entries, observe) ->
            val completion = Completion { observe.appUploaderTopUps() > 0 }
            entries.onBackgroundTask(observe.identifiers.uploadHeartbeatTask, completion::release)
            completion.assertReleasedOnceAfterTheWork()
            entries.onBackgroundTaskTimeUp(observe.identifiers.uploadHeartbeatTask)
            completion.assertReleasedOnce()
        }

        clause("TIME_UP_FOR_AN_UNKNOWN_TASK_IS_IGNORED", PlatformEntriesState.JOINED_WITH_FOREIGN_PHOTO) {
            (entries, observe) ->
            entries.onBackgroundTaskTimeUp("app.example.not-registered")
            settle()
            assertEquals(0, observe.appUploaderTopUps(), "an expiry starts no work")
            assertEquals(0, observe.heartbeatsScheduled(), "and queues nothing")
        }

        clause("UPLOAD_TRANSFERS_RELEASE_AT_THE_DRAIN_THEN_RUN_THE_TAIL", PlatformEntriesState.JOINED_WITH_FOREIGN_PHOTO) {
            (entries, observe) ->
            val completion = Completion { observe.appUploaderTransferHandbacks() == 1 && observe.appUploaderTopUps() == 0 }
            entries.onBackgroundTransfers(observe.identifiers.uploadTransferChannel, completion::release)
            completion.assertReleasedOnceAfterTheWork()
            assertTrue(eventually { observe.appUploaderTopUps() > 0 }, "the tail tops up after the release")
            assertTrue(eventually { observe.backgroundTimeHolds() == 0 }, "and the wake's hold ends with it")
        }

        clause("OTHER_TRANSFERS_ARE_ADOPTED_BY_THE_DOWNLOADS", PlatformEntriesState.JOINED_WITH_FOREIGN_PHOTO) {
            (entries, observe) ->
            entries.onBackgroundTransfers(observe.identifiers.downloadTransferChannel) {}
            assertTrue(eventually { observe.downloadSessionRealized() }, "the download session is brought up")
            assertEquals(0, observe.appUploaderTransferHandbacks(), "and the app uploader is not handed them")
        }

        clause("A_DRAIN_REPORT_THAT_NEVER_COMES_RELEASES_ON_EXPIRY", PlatformEntriesState.JOINED_WITH_FOREIGN_PHOTO) {
            (entries, observe) ->
            val completion = Completion { true }
            entries.onBackgroundTransfers(observe.identifiers.downloadTransferChannel, completion::release)
            assertTrue(eventually { observe.downloadSessionRealized() }, "the download session is brought up")
            settle()
            completion.assertNotReleased("no clock of the app's own releases it")
            observe.expireBackgroundTime()
            completion.assertReleasedOnce()
            assertTrue(eventually { observe.backgroundTimeHolds() == 0 }, "and the hold ends")
            settle()
            assertEquals(0, observe.appUploaderTopUps(), "a wake whose time is up requests no tail")
        }
    }

    private const val TOKEN = "0a1b2c3d4e5f60718293a4b5c6d7e8f9"
}

private operator fun PlatformEntriesSubject.component1() = entries

private operator fun PlatformEntriesSubject.component2() = observe

/**
 * An operating-system completion handler as a clause hands one over: it counts its releases and records whether
 * [workDone] held at the first — "released after the work" is a statement about that instant.
 */
private class Completion(private val workDone: () -> Boolean) {
    private var releases = 0
    private var doneAtRelease: Boolean? = null

    fun release() {
        if (doneAtRelease == null) doneAtRelease = workDone()
        releases++
    }

    fun assertNotReleased(message: String) = assertEquals(0, releases, message)

    /** Released, and exactly once — whether after the work or on the operating system's expiry. */
    suspend fun assertReleasedOnce() {
        assertTrue(eventually { releases > 0 }, "the completion is released")
        settle()
        assertEquals(1, releases, "the completion is released exactly once")
    }

    suspend fun assertReleasedOnceAfterTheWork() {
        assertTrue(eventually { releases > 0 }, "the completion is released")
        // Give a second release the chance to happen before asserting there was none.
        settle()
        assertEquals(1, releases, "the completion is released exactly once")
        assertEquals(true, doneAtRelease, "the completion is released after the wake's own work, and before its tail")
    }
}

/** Polls [condition] in real time until it holds or [within] expires; `true` if it held. */
internal suspend fun eventually(within: Duration = 10.seconds, condition: () -> Boolean): Boolean =
    withContext(Dispatchers.Default) {
        withTimeoutOrNull(within) {
            while (!condition()) delay(POLL)
            true
        } ?: false
    }

/** A short real-time pause, for asserting that something did NOT happen after a thing that did. */
internal suspend fun settle() = withContext(Dispatchers.Default) { delay(SETTLE) }

private val POLL = 10.milliseconds
private val SETTLE = 200.milliseconds
