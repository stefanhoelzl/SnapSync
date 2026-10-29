package app.snapsync.integration

import app.snapsync.client.RigClient
import app.snapsync.control.SCREEN_WAIT
import app.snapsync.control.person
import app.snapsync.levers.BackendLevers
import app.snapsync.levers.ClockLevers
import app.snapsync.levers.DevControlsLevers
import app.snapsync.levers.DownloadSessionLevers
import app.snapsync.levers.ExtensionHostLevers
import app.snapsync.levers.LibraryLevers
import app.snapsync.levers.OsLevers
import app.snapsync.levers.PhotoGrant
import app.snapsync.levers.PublicBackendLevers
import app.snapsync.levers.UploadQueueLevers
import app.snapsync.levers.UploadSessionLevers
import app.snapsync.levers.WorldEvent
import app.snapsync.levers.WorldLevers
import app.snapsync.ui.state.DirectionCount
import app.snapsync.ui.state.JoinPhase
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.ShareCount
import app.snapsync.ui.state.SyncHealth
import app.snapsync.ui.state.UiState
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

// The marketing screenshots' states (`docs/deployment.md`, "Screenshots"), each reached the way a person reaches it:
// the world seeded through the mocks' operator faces, the app driven through the intents a tap produces. So every
// committed raw is a screen the real reduction reached. `ShotsTest` runs them on the JVM host on every build;
// `:test:integration:screenshots` runs them against the rig build on an iOS simulator or an Android emulator and captures
// each (`screenshots.yml`). Both wait the same way: on the host's events, served from where the latest act began
// (`RigClient.awaitScreen`, `RigClient.awaitEvent`).

/** One marketing screenshot: its file name's stem, and the screen it settles on. */
enum class Shot(val id: String, val settled: (UiState) -> Boolean) {
    /** The landing screen: no event yet, the create form seeded from the clock. */
    CREATE("create", { it.layer is Layer.CreateEvent }),

    /** The join confirmation a scanned QR opens: the event loaded, the member's photos counted, ready to confirm. */
    JOINING("joining", { ui ->
        val gate = ui.layer as? Layer.JoiningEvent
        (gate?.phase as? JoinPhase.Detailed)?.step == JoinPhase.Detailed.Step.Ready &&
            (gate.range?.shareCount as? ShareCount.Ready)?.count == Shot.OWN_PHOTOS
    }),

    /** Joined and settled: every own photo shared, every other member's received. */
    IN_SYNC("in_sync", { ui ->
        val joined = ui.layer as? Layer.Joined
        joined?.health == SyncHealth.InSync &&
            (joined.counts?.received as? DirectionCount.Progress)?.let { it.total > 0 && it.complete } == true
    }),
    ;

    companion object {
        fun ofId(id: String): Shot? = entries.firstOrNull { it.id == id }

        /** The clock every shot reads — during the event, and the status bar's 9:41 in the clock's zone (UTC). */
        const val NOW = "2026-07-21T09:41:00Z"

        const val EVENT_NAME = "Anna's Birthday"

        /** The event's id: the invite QR `in_sync` renders encodes it, so it is fixed, never minted at random. */
        const val EVENT_ID = "00000000-0000-4000-8000-000000000000"

        /** The event's range, as the create form takes it: local date-times, five days. */
        const val EVENT_START = "2026-07-20T18:00:00"
        const val EVENT_END = "2026-07-25T18:00:00"

        /** How many photos this device took during the event, and how many the other members did. */
        const val OWN_PHOTOS = 34
        const val THEIR_PHOTOS = 21

        /**
         * How long a shot waits for what its acts lead to: a bound on a hung host, never a pace — a simulator imports
         * and reduces a few dozen photos in seconds.
         */
        val WAIT: Duration = 60.seconds
    }
}

/**
 * Drive this host's app, from an empty world, to [shot]'s screen, and return once it shows it. [relaunch] is the app's
 * process death and next launch over the same mocked systems: the create form reads the clock once, when it first
 * appears, so the clock is set before the launch that shows anything.
 *
 * The host must serve its events from where its latest act began, as a device host does — the JVM host started so
 * (`JvmRigHost.start(serving = …)`) — so a wait that follows an act misses nothing of it and sees nothing before it.
 */
suspend fun RigClient.reach(shot: Shot, relaunch: suspend RigClient.() -> Unit) {
    // The clock last: a launch-adapters host saves every changed system together, so the clock saved means both were.
    levers(LibraryLevers).permission(PhotoGrant.GRANTED)
    advanceTo(Shot.NOW)
    relaunch()
    // Plain events, as a shipped build creates them (`PENDING_CLEANUPS.md`): the other member's photos are seeded
    // as plain bytes, which an encrypted event's real cipher could not open, and an encrypted event's invite QR
    // would carry a random key — a different `in_sync` raw on every run. The switch is the running process's, so it
    // follows the launch.
    levers(DevControlsLevers).encryptNewEvents(false)
    val person = person()
    when (shot) {
        Shot.CREATE -> Unit
        Shot.JOINING -> {
            takePhotos()
            // The gate a create leaves open on the loaded event: the same screen a scanned QR opens, reached without
            // the invite — a rig on a device does not hand out an event's key, which only its share link carries.
            person.createEvent(Shot.EVENT_NAME, Shot.EVENT_START, Shot.EVENT_END)
        }
        Shot.IN_SYNC -> {
            takePhotos()
            levers(BackendLevers).nextEventId(Shot.EVENT_ID)
            // Where the host composes the OS-driven extension, its cycle uploads (the app's own uploader would take the
            // same photos, so it is switched off); where it does not — Android — the app's uploader does, and the
            // operating system lands its transfers.
            val osDriven = hasOsDrivenUploader()
            if (osDriven) levers(DevControlsLevers).pin(app = false, extension = true)
            person.createEvent(Shot.EVENT_NAME, Shot.EVENT_START, Shot.EVENT_END)
            awaitScreen(SCREEN_WAIT, "the created event's join gate, its details loaded") {
                (it.layer as? Layer.JoiningEvent)?.phase is JoinPhase.Detailed
            }
            person.confirmJoin()
            awaitScreen(SCREEN_WAIT, "the joined screen") { it.layer is Layer.Joined }
            if (osDriven) uploadThroughTheExtension() else landAppUploads()
            levers(PublicBackendLevers).foreignDevice(GUEST_DEVICE, List(Shot.THEIR_PHOTOS) { "GUEST-${it + 1}" })
            receiveTheGuestsPhotos()
        }
    }
    awaitScreen(Shot.WAIT, "the ${shot.id} shot's screen", shot.settled)
}

/**
 * The clock moves to [at], an ISO instant: what the app reads from then on. The JVM host's world moves it, with the
 * timers due on the way; a device host, which serves no world clock, sets its mocked clock.
 */
private suspend fun RigClient.advanceTo(at: String) =
    if ("world.advanceTo" in device().honoured) levers(WorldLevers).advanceTo(at) else levers(ClockLevers).set(at)

/** This device's photos of the event, one every twenty minutes from its start. */
private suspend fun RigClient.takePhotos() {
    val start = Instant.parse(Shot.EVENT_START + "Z")
    val library = levers(LibraryLevers)
    repeat(Shot.OWN_PHOTOS) { i -> library.add("IMG-${i + 1}", date = (start + (20 * (i + 1)).minutes).toString()) }
}

/** Whether this host composes the OS-driven upload extension: the iOS simulator and the JVM host do, Android does not. */
private suspend fun RigClient.hasOsDrivenUploader(): Boolean = "device/uploaders" in device().honoured

/**
 * Every own photo uploaded by the extension: a cycle creates their jobs, the operating system lands each, and a cycle
 * settles and publishes them. Each cycle is answered once it is done, so no wait stands between them.
 */
private suspend fun RigClient.uploadThroughTheExtension() {
    val extension = levers(ExtensionHostLevers)
    extension.process()
    levers(UploadQueueLevers).completeJob()
    extension.process()
}

/**
 * The operating system lands the app uploader's transfers, batch by batch as the app creates them, until it has created
 * one for every own photo and none is left in flight — what the operating system's session records. The uploader
 * creates them from its own tail, so after each landing the next batch is waited for on the events from that landing
 * on: each check follows an act, so a wait never matches what came before it.
 */
private suspend fun RigClient.landAppUploads() {
    val session = levers(UploadSessionLevers)
    while (true) {
        session.complete()
        val now = session.session()
        if (now.created.size >= Shot.OWN_PHOTOS && now.live.isEmpty()) return
        if (now.live.isEmpty()) {
            awaitEvent(Shot.WAIT, "the app uploader's next transfer") { it as? WorldEvent.SessionTransferCreated }
        }
    }
}

/**
 * The guest's photos come in: the foreground's reconcile reads the union and starts a download for each, and the
 * operating system finishes them all; the app stages and imports each, and its next foreground reads the status that
 * counts them.
 */
private suspend fun RigClient.receiveTheGuestsPhotos() {
    levers(OsLevers).foreground()
    val started = mutableSetOf<String>()
    awaitEvent(Shot.WAIT, "a download started for each of the guest's ${Shot.THEIR_PHOTOS} photos") { event ->
        (event as? WorldEvent.DownloadStarted)?.let { started += it.description }
        Unit.takeIf { started.size == Shot.THEIR_PHOTOS }
    }
    levers(DownloadSessionLevers).finish()
    val imported = mutableSetOf<String>()
    awaitEvent(Shot.WAIT, "each of the guest's ${Shot.THEIR_PHOTOS} photos imported") { event ->
        (event as? WorldEvent.LibraryAssetCreated)?.asset?.takeIf { it.startsWith("imported-$GUEST_DEVICE-") }
            ?.let { imported += it }
        Unit.takeIf { imported.size == Shot.THEIR_PHOTOS }
    }
    levers(OsLevers).foreground()
}
