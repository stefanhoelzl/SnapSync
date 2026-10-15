package app.snapsync.levers

import app.snapsync.ui.state.UiState
import kotlinx.coroutines.flow.Flow
import kotlinx.rpc.annotations.Rpc
import kotlinx.rpc.descriptor.serviceDescriptorOf
import kotlinx.rpc.internal.utils.ExperimentalRpcApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * **The world the app runs in, as a test waits on it** (`docs/testing.md`, "How a test waits"): every event in the order
 * it happened, and the clock. Every host serves the events; a host that cannot say when its app is settled — a device,
 * whose operating system runs work the channel cannot see — refuses the clock and [whyNotSettled].
 */
@Rpc
interface WorldLevers {
    /**
     * Every event kept, then every event as it happens: what the app showed, what the systems outside it recorded, each
     * act a caller made, and each time the app settled. The JVM host keeps every event since it started, so a caller
     * that subscribes after its first act misses nothing and reads from its own act's [WorldEvent.Acted] on; a device
     * host, whose app outlives any one caller, serves from where its latest act began.
     */
    fun events(): Flow<WorldEvent>

    /**
     * Marks every act this host runs from now on with [tag] — until another tag, or `null` for none — so a caller finds
     * where its acts begin and end in [events].
     */
    suspend fun tagActs(tag: String?)

    /** How many acts this host has run marked with [tag]. */
    suspend fun actsTagged(tag: String): Int

    /**
     * Moves the mocked clock [millis] ahead, firing every timer due on the way at its own instant and settling between
     * them. An act: it is tagged and announced like any other.
     */
    suspend fun advance(millis: Long)

    /**
     * Moves the clock to [at], an ISO instant, firing every timer due on the way at its own instant and settling between
     * them; an instant before now moves the clock back and fires nothing. On a host whose timers run on the wall clock it
     * moves only the clock the app reads. An act, like [advance].
     */
    suspend fun advanceTo(at: String)

    /** Why the app is not settled: what runs, what is queued, what work outside the app it waits on. */
    suspend fun whyNotSettled(): String

    @OptIn(ExperimentalRpcApi::class)
    companion object : LeverService<WorldLevers>(WorldLevers::class, "world", emptySet(), { serviceDescriptorOf() })
}

/** One thing that happened in the world, as [WorldLevers.events] tells it. */
@Serializable
sealed interface WorldEvent {
    /** A caller's act began: everything after it in the stream is after the act. */
    @Serializable
    @SerialName("acted")
    data class Acted(val name: String, val tag: String? = null) : WorldEvent

    /** The app showed [ui] on its screen. */
    @Serializable
    @SerialName("ui")
    data class UiChanged(val ui: UiState) : WorldEvent

    /**
     * Nothing in the app runs or is runnable at [now], and nothing outside it is in flight but what an operator holds.
     * [nextTimer] is when the app's next timer is due — `null` when none is pending, so nothing will happen until the
     * world moves. [state] is what systems outside the app record at that moment.
     */
    @Serializable
    @SerialName("settled")
    data class Settled(val now: String, val nextTimer: String? = null, val state: SettledState = SettledState()) :
        WorldEvent

    /** Work reached the app from a thread the host's loop does not own — what would make a [Settled] lie. */
    @Serializable
    @SerialName("lane-violation")
    data class LaneViolation(val what: String) : WorldEvent

    /**
     * A statement of [kind] on [table] of [database] failed, as an operator's [DatabasesLevers.fail] armed it: the fault
     * was spent, on the statement the app made.
     */
    @Serializable
    @SerialName("database-faulted")
    data class DatabaseFaulted(val database: String, val table: String, val kind: WriteKind) : WorldEvent

    /** A Keychain call of [kind] on [item] was refused, as an operator's [KeychainLevers.fail] armed it. */
    @Serializable
    @SerialName("keychain-faulted")
    data class KeychainFaulted(val item: KeychainItem, val kind: StoreFault) : WorldEvent

    /** A preferences call of [kind] on [item] was refused, as an operator's [PreferencesLevers.fail] armed it. */
    @Serializable
    @SerialName("preferences-faulted")
    data class PreferencesFaulted(val item: PreferenceItem, val kind: StoreFault) : WorldEvent

    /** What the backend recorded. */
    @Serializable
    sealed interface Backend : WorldEvent

    /** A request reached the backend: the device API's [operation] (its OpenAPI `operationId`). */
    @Serializable
    @SerialName("backend-received")
    data class BackendReceived(val operation: String) : Backend

    /** A request is parked on an operator's hold of [call], and stays parked until it is released. */
    @Serializable
    @SerialName("backend-held")
    data class BackendHeld(val call: BackendCall) : Backend

    /**
     * What [event] offers its members changed: [device]'s photo [asset] became servable ([gained]) — every byte it
     * declares landed — or stopped being ([gained] `false`): no longer declared. What another member's next read gets.
     */
    @Serializable
    @SerialName("union-changed")
    data class UnionChanged(val event: String, val device: String, val asset: String, val gained: Boolean) : Backend

    /** What the operating system's background transfers recorded: the work the app handed them. */
    @Serializable
    sealed interface Transfer : WorldEvent

    /** The app created an upload job on the operating system's queue, named by what its destination names. */
    @Serializable
    @SerialName("upload-job-created")
    data class UploadJobCreated(val job: String) : Transfer

    /** The app handed its upload session a transfer, tagged with its upload [key]. */
    @Serializable
    @SerialName("session-transfer-created")
    data class SessionTransferCreated(val key: String) : Transfer

    /** The app started a download, under its [description]. */
    @Serializable
    @SerialName("download-started")
    data class DownloadStarted(val description: String) : Transfer

    /**
     * An upload job left the operating system's queue with [outcome]: the app acknowledged it — its handback, which is
     * when the queue lets a job go. A failed job the app retries has not ended.
     */
    @Serializable
    @SerialName("upload-job-ended")
    data class UploadJobEnded(val job: String, val outcome: TransferEnd) : Transfer

    /** The upload session's transfer tagged [key] ended with [outcome]: it landed, failed, or the app cancelled it. */
    @Serializable
    @SerialName("session-transfer-ended")
    data class SessionTransferEnded(val key: String, val outcome: TransferEnd) : Transfer

    /** The download [description] ended with [outcome]: the session finished it, or the app cancelled it. */
    @Serializable
    @SerialName("download-ended")
    data class DownloadEnded(val description: String, val outcome: TransferEnd) : Transfer

    /** What the photo library recorded. */
    @Serializable
    sealed interface Library : WorldEvent

    /** A walk of the library is parked on the operator's enumeration hold, and stays parked until it is released. */
    @Serializable
    @SerialName("library-enumeration-held")
    data object LibraryEnumerationHeld : Library

    /** An add to the app's album [album] is parked on the operator's hold of adds. */
    @Serializable
    @SerialName("library-add-held")
    data class LibraryAddHeld(val album: String) : Library

    /** An import of the photo [asset] of the member device [device] is parked, as the operator asked, until resumed. */
    @Serializable
    @SerialName("library-import-parked")
    data class LibraryImportParked(val device: String, val asset: String) : Library

    /** An import created the photo [asset] in the library. */
    @Serializable
    @SerialName("library-asset-created")
    data class LibraryAssetCreated(val asset: String) : Library

    /** The app made the album [album], titled [title]. */
    @Serializable
    @SerialName("library-album-created")
    data class LibraryAlbumCreated(val album: String, val title: String) : Library

    /** The photo [asset] is now in the app's album [album] — once per photo, as a person would see it arrive. */
    @Serializable
    @SerialName("library-joined-album")
    data class LibraryJoinedAlbum(val album: String, val asset: String) : Library

    /**
     * The platform's photo-access dialog was raised — the app asked while the grant was still undecided, the one time a
     * platform asks.
     */
    @Serializable
    @SerialName("library-access-asked")
    data object LibraryAccessAsked : Library

    /** The platform's photo picker was presented to widen a partial selection — the one grant it has a surface under. */
    @Serializable
    @SerialName("library-picker-shown")
    data object LibraryPickerShown : Library

    /** What the platform's own UI recorded: what the app handed it. */
    @Serializable
    sealed interface HandedOver : WorldEvent

    /** The app presented the share sheet with [text], under [title]. */
    @Serializable
    @SerialName("shared")
    data class Shared(val text: String, val title: String) : HandedOver

    /** The app asked the platform to open [url]. */
    @Serializable
    @SerialName("url-opened")
    data class UrlOpened(val url: String) : HandedOver

    /** The app opened its Settings page. */
    @Serializable
    @SerialName("settings-opened")
    data object SettingsOpened : HandedOver

    /** The crash reporter's backend received a diagnostic [dump]. */
    @Serializable
    @SerialName("dump-received")
    data class DumpReceived(val dump: ReceivedDump) : WorldEvent

    /** The backend stored [device]'s registration — its push [token], or none — as `putDevice` stores it. */
    @Serializable
    @SerialName("backend-device-registered")
    data class DeviceRegistered(val device: String, val token: String?) : Backend

    /** The backend sent [device] a silent push for [event], to [token] — what the push service delivers. */
    @Serializable
    @SerialName("backend-push-sent")
    data class PushSent(val event: String, val device: String, val token: String) : Backend

    // What the backend STORED — one event per stored change, each naming exactly what changed, told by the backend mock
    // from its own state as it changes it (a real backend tells none of these). Whoever caused it: the app's request, or
    // an operator playing another member.

    /** The backend stored a new event [event], named [name]. */
    @Serializable
    @SerialName("backend-event-created")
    data class EventCreated(val event: String, val name: String) : Backend

    /** The backend enrolled [device] in [event] — a first join, or a rejoin of a member who had left. */
    @Serializable
    @SerialName("backend-member-joined")
    data class MemberJoined(val event: String, val device: String) : Backend

    /** [device] left [event]: its membership is gone, and what it shared stays in the union. */
    @Serializable
    @SerialName("backend-member-left")
    data class MemberLeft(val event: String, val device: String) : Backend

    /**
     * The backend applied [device]'s manifest for [event] at [version]: [added] are the asset ids it declares that the
     * one it replaced did not, [withdrawn] those it no longer declares, and [final] whether it declares the device's
     * share settled. Every applied publish is told — one that changes no asset (a republish under a newer version) with
     * both lists empty; one answered and not applied (older, closed) is not.
     */
    @Serializable
    @SerialName("backend-manifest-published")
    data class ManifestPublished(
        val event: String,
        val device: String,
        val version: Long?,
        val added: List<String>,
        val withdrawn: List<String>,
        val final: Boolean,
    ) : Backend

    /** The bytes of [asset]'s [role] (its wire name: `original`, `paired-video`, …) from [device] landed in [event]. */
    @Serializable
    @SerialName("backend-bytes-stored")
    data class BytesStored(val event: String, val device: String, val asset: String, val role: String) : Backend

    /** [device] reported holding every photo of [event]'s other members up to the union's [position]. */
    @Serializable
    @SerialName("backend-received-reported")
    data class ReceivedReported(val event: String, val device: String, val position: Long) : Backend

    /** The backend stored [name] as [event]'s name. */
    @Serializable
    @SerialName("backend-event-renamed")
    data class EventRenamed(val event: String, val name: String) : Backend

    /** The backend deleted [event]'s photos — the sweep's completion — or the event itself. */
    @Serializable
    @SerialName("backend-event-deleted")
    data class EventDeleted(val event: String) : Backend

    /** What the operating system recorded of the app, as it happened — never an app-side fact. */
    @Serializable
    sealed interface Os : WorldEvent

    /** The operating system accepted a wake request — one replaces the pending one of its [wake]. */
    @Serializable
    @SerialName("wake-scheduled")
    data class WakeScheduled(val wake: PendingWake) : Os

    /** The app cancelled its request for [wake] (`heartbeat`, `library-changed`). */
    @Serializable
    @SerialName("wake-cancelled")
    data class WakeCancelled(val wake: String) : Os

    /** The app began a background-time hold, under [name] — as `beginBackgroundTask(withName:)` names it, verbatim. */
    @Serializable
    @SerialName("hold-begun")
    data class HoldBegun(val name: String) : Os

    /** The app ended the background-time hold it began under [name]. */
    @Serializable
    @SerialName("hold-ended")
    data class HoldEnded(val name: String) : Os

    /** The app released a completion handler the operating system handed it — [again] for one already released. */
    @Serializable
    @SerialName("completion-released")
    data class CompletionReleased(val again: Boolean) : Os

    /** The app asked the push service for its token. */
    @Serializable
    @SerialName("push-registered")
    data object PushRegistered : Os

    /**
     * The member force-quit the app: its process died, its background transfers were cancelled, and the operating system
     * launches it for nothing until the member opens it again ([PlayedOsLevers.forceQuit]).
     */
    @Serializable
    @SerialName("force-quit")
    data object ForceQuit : Os

    /** The upload extension's registration was set [on] or off, as the operating system took the app's request. */
    @Serializable
    @SerialName("extension-registered")
    data class ExtensionRegistered(val on: Boolean) : Os

    /** The crash reporter's backend received an automatic [event]: a failure the app reported on its own. */
    @Serializable
    @SerialName("event-received")
    data class EventReceived(val event: ReceivedEvent) : WorldEvent
}

/**
 * What systems outside the app record at a [WorldEvent.Settled], read at exactly that settle — so a test judges the
 * settle it waited for, never a later read.
 *
 * A field joins only when it is (a) something a system outside the app records — the observability rule
 * (`docs/testing.md` §7, "The rules") holds here too; (b) cheap to read at every settle; and (c) asserted by some test at a
 * settle. `null` where the system it reads is not the mock's.
 */
@Serializable
data class SettledState(
    /**
     * The bytes the app's files take on the device — what Settings' storage page shows a person — its diagnostics (the
     * device logs, the memory trail, a saved report) left out, which grow with whatever the app does and would drown out
     * what a test measures.
     */
    val appStorageBytes: Long? = null,
    /** Every photo the library holds, by id, in the order it came. */
    val libraryPhotos: List<String>? = null,
    /** The albums the app made, as the library holds each now. */
    val albums: List<SettledAlbum>? = null,
    /** The transfers the operating system still holds for the app. */
    val inFlight: InFlight? = null,
    /** The events the backend holds, by id: what it derives of each from its clock and its members' reports. */
    val backendEvents: Map<String, SettledEvent>? = null,
)

/** An album the app made, as the library holds it: its [photos] each once, while the library holds it. */
@Serializable
data class SettledAlbum(val id: String, val title: String, val photos: List<String>)

/** The transfers the operating system holds for the app, each kind by what names it. */
@Serializable
data class InFlight(
    /** The upload jobs on the OS's queue, not yet acknowledged — by job name (`<asset>/<role>`). */
    val jobs: List<String>,
    /** The upload session's live transfers, by upload key. */
    val sessionTransfers: List<String>,
    /** The downloads not yet finished or cancelled, by description. */
    val downloads: List<String>,
)

/**
 * What the backend derives of an event: [finished] — its open-until date passed and every member still in it shared
 * and received everything — and [closedForOldBuilds], what the builds that predate open-until dates read as closed
 * (`PENDING_CLEANUPS.md`).
 */
@Serializable
data class SettledEvent(val finished: Boolean, val closedForOldBuilds: Boolean)

/** How a transfer ended: [how], and the [failure] the system reported for a failed one. */
@Serializable
data class TransferEnd(val how: How, val failure: String? = null) {
    enum class How { DELIVERED, FAILED, CANCELLED }

    companion object {
        val DELIVERED = TransferEnd(How.DELIVERED)
        val CANCELLED = TransferEnd(How.CANCELLED)

        fun failed(failure: String) = TransferEnd(How.FAILED, failure)
    }
}
