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
}
