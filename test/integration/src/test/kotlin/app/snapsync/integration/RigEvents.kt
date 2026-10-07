package app.snapsync.integration

import app.snapsync.model.EventLinkPayload
import app.snapsync.model.JoinPhase
import app.snapsync.model.Layer
import app.snapsync.model.encodeEventUrl
import app.snapsync.rig.AssetView

// The protocol calls that make up a person's events and photos — joining, creating, leaving, the library.

/**
 * Create an event over the join gate, as a person does, and wait for the gate to load its details. Answers the
 * minted event id; the gate is left open for [join], [cancelJoin] or a test's own confirm.
 *
 * The default window contains [Rig.PHOTO_DATE], the capture date every added and seeded photo carries.
 */
suspend fun Rig.create(
    name: String = Rig.EVENT_NAME,
    startsAt: String = Rig.WINDOW_START,
    endsAt: String = Rig.WINDOW_END,
): String {
    user("create", "name" to name, "startsAt" to startsAt, "endsAt" to endsAt)
    val gate = awaitState { (it.ui.layer as? Layer.JoiningEvent)?.phase is JoinPhase.Detailed }
    return (gate.ui.layer as Layer.JoiningEvent).eventId
}

/** Confirm the open join gate with [choices] (`direction`, `saveToAlbum`, `cutoff`, `until`), and wait to be joined. */
suspend fun Rig.join(vararg choices: Pair<String, String>): String {
    user("confirmJoin", *choices)
    return awaitState { it.ready.configResolved }.ready.eventId!!
}

/**
 * Wait until the event album exists, and answer every album. A join or a Save is reported as soon as its membership is
 * saved, and the album is ensured only after that — so a test that reads, places into or imports into the album right
 * after one waits here first.
 */
suspend fun Rig.awaitAlbum(): List<Album> = eventually(read = { albums() }) { it.isNotEmpty() }

/** Create an event and join it. Answers its id. */
suspend fun Rig.createAndJoin(
    vararg choices: Pair<String, String>,
    name: String = Rig.EVENT_NAME,
    startsAt: String = Rig.WINDOW_START,
    endsAt: String = Rig.WINDOW_END,
): String {
    create(name, startsAt, endsAt)
    return join(*choices)
}

/** An event that exists on the backend and that this device has not joined: created, then the gate abandoned. */
suspend fun Rig.registerEvent(
    name: String = Rig.EVENT_NAME,
    startsAt: String = Rig.WINDOW_START,
    endsAt: String = Rig.WINDOW_END,
): String {
    val event = create(name, startsAt, endsAt)
    user("cancelJoin")
    awaitState { it.ui.layer is Layer.CreateEvent }
    return event
}

/** Open an event link, as the operating system delivers a scanned one. */
suspend fun Rig.openLink(url: String) {
    os("app", "onSceneContinueActivity", url)
}

/** The link a member scans for [eventId]. */
fun Rig.inviteLink(
    eventId: String,
    autoJoin: Boolean = false,
    minPhotoDate: String? = null,
    direction: String? = null,
    saveToAlbum: Boolean? = null,
): String = encodeEventUrl(
    EventLinkPayload(eventId, autoJoin, minPhotoDate, direction = direction, saveToAlbum = saveToAlbum),
)

suspend fun Rig.leave() {
    user("leave")
    awaitState { !it.ready.configResolved }
}

/** Add one own photo — a camera photo unless [kind] says otherwise (`gallery/add`'s kinds). */
suspend fun Rig.addPhoto(id: String, date: String = Rig.PHOTO_DATE, kind: String = "photo") {
    device("gallery/add", "id" to id, "date" to date, "kind" to kind)
}

/** The own photo's asset in the library, by id. */
suspend fun Rig.asset(id: String, resources: Boolean = false): AssetView? =
    gallery(resources).policy?.assets?.firstOrNull { it.assetId == id }

suspend fun Rig.permission(status: String) {
    device("permission", "status" to status)
}
