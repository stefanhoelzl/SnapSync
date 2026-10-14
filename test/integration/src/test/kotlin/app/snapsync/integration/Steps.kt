package app.snapsync.integration

import app.snapsync.control.behaviour.Acts
import app.snapsync.control.behaviour.Given
import app.snapsync.control.behaviour.Seen
import app.snapsync.levers.NetworkState
import app.snapsync.levers.WorldEvent
import app.snapsync.model.ConfigDecodeResult
import app.snapsync.model.EventLinkPayload
import app.snapsync.model.decodeEventUrl
import app.snapsync.model.encodeEventUrl
import app.snapsync.testids.testEventId
import app.snapsync.ui.state.JoinPhase
import app.snapsync.ui.state.Layer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// The shared named steps a behaviour starts from (`docs/testing.md`, "How a test waits"): each one acts and awaits its
// own outcome, so a behaviour's `given` reads as the state it starts in, and no test copies how to get there.

/** An event created over the join gate, the gate open on its details. Answers the minted event id. */
suspend fun Given.createdEvent(
    name: String = Rig.EVENT_NAME,
    startsAt: String = Rig.WINDOW_START,
    endsAt: String = Rig.WINDOW_END,
): String {
    lateinit var gate: app.snapsync.control.behaviour.Seen<Layer.JoiningEvent>
    step(
        "an event created",
        act = { user("create", "name" to name, "startsAt" to startsAt, "endsAt" to endsAt) },
        until = { gate = screen<Layer.JoiningEvent> { it.phase is JoinPhase.Detailed } },
    )
    return gate.value.eventId
}

/** An event that exists on the backend and that this device has not joined: created, then the gate abandoned. */
suspend fun Given.registeredEvent(name: String = Rig.EVENT_NAME): String {
    val event = createdEvent(name)
    step("its join gate abandoned", act = { user("cancelJoin") }, until = { screen<Layer.CreateEvent>() })
    return event
}

/** An event created and joined, on its joined screen. Answers its id. */
suspend fun Given.joinedEvent(vararg choices: Pair<String, String>): String {
    val event = createdEvent()
    step("joined", act = { user("confirmJoin", *choices) }, until = { screen<Layer.Joined>() })
    return event
}

/**
 * An event created and joined saving to its album, and the album made in the library. Answers the event's id and the
 * album's, as the library gave it.
 */
suspend fun Given.joinedSavingToAlbum(name: String = Rig.EVENT_NAME): Pair<String, String> {
    val event = createdEvent(name)
    lateinit var album: Seen<WorldEvent.LibraryAlbumCreated>
    step(
        "joined, saving to its album",
        act = { user("confirmJoin", "saveToAlbum" to "true") },
        until = {
            screen<Layer.Joined>()
            album = event<WorldEvent.LibraryAlbumCreated>()
        },
    )
    return event to album.value.album
}

/** The app comes to the front, and does what it does there until it is settled. */
suspend fun Given.inFront() = step("in front", act = { os("app", "onForeground") }, until = { settled { true } })

/** The link a member scans for [eventId]: its whole invite, an encrypted event's key included. */
suspend fun Given.inviteFor(eventId: String): String =
    step("the invite for $eventId", act = { inviteLink(eventId) }, until = {})

/** [eventId]'s invite, as the device that created it shares it. */
suspend fun Acts.inviteLink(eventId: String): String {
    val invite = Json.parseToJsonElement(
        device("invite", "event" to eventId),
    ).jsonObject["invite"]!!.jsonPrimitive.content
    val key = (decodeEventUrl(invite) as ConfigDecodeResult.Success).payload.key
    return encodeEventUrl(EventLinkPayload(testEventId(eventId), autoJoin = false, minPhotoDate = null, key = key))
}

/** The operating system delivers a scanned event link. */
suspend fun Acts.openLink(url: String) {
    os("app", "onSceneContinueActivity", url)
}

/**
 * The device's network as the operating system reports it, and whether the backend can be reached: on a phone the two
 * are one event, which the operator plays as two levers.
 */
suspend fun Acts.network(access: NetworkState, backendReachable: Boolean = access == NetworkState.ONLINE) {
    backend.offline(!backendReachable)
    network.set(access)
}

/**
 * Only the extension uploads: the app's uploader is switched off. Both run on a phone and the JVM host composes both, so
 * a behaviour about the OS-driven cycle states that it drives that cycle alone.
 */
suspend fun Given.extensionUploadsOnly() =
    step("only the extension uploads", act = { device("uploaders", "app" to "off") }, until = {})

/** Own photos taken on the event's day, in the library, and whatever the app does about them done. */
suspend fun Given.photos(vararg ids: String) = step(
    "photos ${ids.joinToString()} taken",
    act = { ids.forEach { takePhoto(it) } },
    until = { settled { true } },
)

/** An own photo [id] taken on the event's day lands in the library. */
suspend fun Acts.takePhoto(id: String) {
    library.add(id, date = Rig.PHOTO_DATE)
}

/** One upload cycle, as the OS-driven extension runs it. Answers what it tells the system (`completed`, `processing`). */
suspend fun Acts.cycle(): String =
    Json.parseToJsonElement(os("photokit-ext", "processRawValue")).jsonObject.getValue("result").jsonPrimitive.content
