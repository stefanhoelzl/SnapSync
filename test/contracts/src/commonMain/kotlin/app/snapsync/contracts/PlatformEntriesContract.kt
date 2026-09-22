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
     * this device has not downloaded yet. Nothing has run: no download is planned, no backstop queued.
     */
    JOINED_WITH_FOREIGN_PHOTO,
}

/**
 * What a clause reads to see an entry's **outcome** in the app behind the port (capability `port-contracts`: an
 * inbound port declares no reads, so a binding supplies these over the system it built). Every member is a
 * snapshot of that system's state — never a record of which collaborator the implementation called, because a
 * call transcript restates the wiring and is passed by anything that mirrors it.
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

    /** How many times the download backstop has been queued with the operating system. */
    fun backstopsScheduled(): Int

    /** The push tokens that reached the registration source, in order. */
    fun deliveredPushTokens(): List<String>

    /** How many background-task wakes reached the app's uploader. */
    fun appUploaderBackgroundTasks(): Int

    /** How many background-transfer handbacks reached the app's uploader. */
    fun appUploaderTransferHandbacks(): Int

    /** Whether the download session has been brought up to receive handed-back transfers. */
    fun downloadSessionRealized(): Boolean
}

/** The operating system's identifiers for the entries that route by one. */
class EntryIdentifiers(
    val downloadBackstopTask: String,
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
 * used. It is also why an entry that takes a completion is held to **"released exactly once, after the work"** —
 * the completion is part of the port's own signature, and releasing it early or twice is the operating system's
 * business (capability `ios-app-shell`, "OS completion handlers are released only after their work completes").
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

        clause("FOREGROUND_MARKS_ACTIVE_AND_RECONCILES_DOWNLOADS", PlatformEntriesState.JOINED_WITH_FOREIGN_PHOTO) {
            (entries, observe) ->
            entries.onForeground()
            assertTrue(eventually { observe.becameActive() }, "the app records that it became active")
            assertTrue(eventually { observe.plannedForeignDownloads() > 0 }, "the foreground reconcile plans the photo")
        }

        clause("BACKGROUND_QUEUES_THE_BACKSTOP", PlatformEntriesState.JOINED_WITH_FOREIGN_PHOTO) { (entries, observe) ->
            entries.onBackground()
            assertTrue(eventually { observe.backstopsScheduled() == 1 }, "leaving the foreground arms the backstop")
            assertTrue(!observe.becameActive(), "and is not an activation")
        }

        clause("PUSH_TOKEN_REACHES_REGISTRATION", PlatformEntriesState.UNJOINED) { (entries, observe) ->
            entries.onPushToken(TOKEN)
            assertEquals(listOf(TOKEN), observe.deliveredPushTokens())
        }

        clause("SILENT_PUSH_RECONCILES_DOWNLOADS_THEN_RELEASES", PlatformEntriesState.JOINED_WITH_FOREIGN_PHOTO) {
            (entries, observe) ->
            val completion = Completion { observe.plannedForeignDownloads() > 0 }
            entries.onSilentPush(mapOf<Any?, Any?>("eventId" to observe.joinedEventId), completion::release)
            completion.assertReleasedOnceAfterTheWork()
        }

        clause("SILENT_PUSH_WITHOUT_AN_EVENT_STILL_RELEASES", PlatformEntriesState.JOINED_WITH_FOREIGN_PHOTO) {
            (entries, observe) ->
            val completion = Completion { true }
            entries.onSilentPush(emptyMap<Any?, Any?>(), completion::release)
            completion.assertReleasedOnceAfterTheWork()
            assertEquals(0, observe.plannedForeignDownloads(), "a push naming no event reaches no arm")
        }

        clause("BACKSTOP_TASK_RUNS_AND_REQUEUES", PlatformEntriesState.JOINED_WITH_FOREIGN_PHOTO) { (entries, observe) ->
            val completion = Completion { true }
            entries.onBackgroundTask(observe.identifiers.downloadBackstopTask, completion::release)
            completion.assertReleasedOnceAfterTheWork()
            assertTrue(eventually { observe.backstopsScheduled() == 1 }, "the backstop re-queues itself however it ends")
            assertEquals(0, observe.appUploaderBackgroundTasks(), "and is not the upload heartbeat")
        }

        clause("HEARTBEAT_TASK_REACHES_THE_APP_UPLOADER", PlatformEntriesState.JOINED_WITH_FOREIGN_PHOTO) {
            (entries, observe) ->
            val completion = Completion { observe.appUploaderBackgroundTasks() == 1 }
            entries.onBackgroundTask(observe.identifiers.uploadHeartbeatTask, completion::release)
            completion.assertReleasedOnceAfterTheWork()
            assertEquals(0, observe.backstopsScheduled(), "the heartbeat is not the download backstop")
        }

        clause("UNKNOWN_TASK_IS_RELEASED_WITHOUT_WORK", PlatformEntriesState.JOINED_WITH_FOREIGN_PHOTO) {
            (entries, observe) ->
            val completion = Completion { true }
            entries.onBackgroundTask("app.example.not-registered", completion::release)
            completion.assertReleasedOnceAfterTheWork()
            assertEquals(0, observe.appUploaderBackgroundTasks(), "no uploader wake")
            assertEquals(0, observe.backstopsScheduled(), "no backstop")
        }

        clause("UPLOAD_TRANSFERS_REACH_THE_APP_UPLOADER", PlatformEntriesState.JOINED_WITH_FOREIGN_PHOTO) {
            (entries, observe) ->
            val completion = Completion { observe.appUploaderTransferHandbacks() == 1 }
            entries.onBackgroundTransfers(observe.identifiers.uploadTransferChannel, completion::release)
            completion.assertReleasedOnceAfterTheWork()
        }

        clause("OTHER_TRANSFERS_ARE_ADOPTED_BY_THE_DOWNLOADS", PlatformEntriesState.JOINED_WITH_FOREIGN_PHOTO) {
            (entries, observe) ->
            entries.onBackgroundTransfers(observe.identifiers.downloadTransferChannel) {}
            assertTrue(eventually { observe.downloadSessionRealized() }, "the download session is brought up")
            assertEquals(0, observe.appUploaderTransferHandbacks(), "and the app uploader is not handed them")
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

    suspend fun assertReleasedOnceAfterTheWork() {
        assertTrue(eventually { releases > 0 }, "the completion is released")
        // Give a second release the chance to happen before asserting there was none.
        settle()
        assertEquals(1, releases, "the completion is released exactly once")
        assertEquals(true, doneAtRelease, "the completion is released after the work, not before it")
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
