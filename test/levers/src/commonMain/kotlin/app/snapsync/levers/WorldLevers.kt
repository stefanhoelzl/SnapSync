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
 * it happened, and the clock. A host that cannot say when its app is settled — a device, whose operating system runs
 * work the channel cannot see — refuses it.
 */
@Rpc
interface WorldLevers {
    /**
     * Every event since the host started, then every event as it happens: what the app showed, what the systems outside
     * it recorded, each act a caller made, and each time the app settled. From the start, so a caller that subscribes
     * after its first act misses nothing; a caller reads from its own act's [WorldEvent.Acted] on.
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
     * world moves.
     */
    @Serializable
    @SerialName("settled")
    data class Settled(val now: String, val nextTimer: String? = null) : WorldEvent

    /** Work reached the app from a thread the host's loop does not own — what would make a [Settled] lie. */
    @Serializable
    @SerialName("lane-violation")
    data class LaneViolation(val what: String) : WorldEvent

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
}
