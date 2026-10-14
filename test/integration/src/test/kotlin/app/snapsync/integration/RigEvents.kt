package app.snapsync.integration

import app.snapsync.levers.LibraryPhotoKind
import app.snapsync.levers.ParkedImport
import app.snapsync.levers.PhotoGrant
import app.snapsync.model.ConfigDecodeResult
import app.snapsync.model.EventLinkPayload
import app.snapsync.model.decodeEventUrl
import app.snapsync.model.encodeEventUrl
import app.snapsync.rig.AssetView
import app.snapsync.testids.testEventId
import app.snapsync.ui.state.JoinPhase
import app.snapsync.ui.state.Layer
import kotlinx.serialization.json.jsonPrimitive

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
    os.continueLink(url)
}

/**
 * The link a member scans for [eventId]: its whole invite — carrying an encrypted event's key, as the device that
 * created it shares it — with any development hints added.
 */
suspend fun Rig.inviteLink(
    eventId: String,
    autoJoin: Boolean = false,
    minPhotoDate: String? = null,
    direction: String? = null,
    saveToAlbum: Boolean? = null,
): String = encodeEventUrl(
    EventLinkPayload(
        testEventId(eventId),
        autoJoin,
        minPhotoDate,
        direction = direction,
        saveToAlbum = saveToAlbum,
        key = keyOf(eventId),
    ),
)

/** [eventId]'s key as its invite carries it, or `null` for a plain event. */
suspend fun Rig.keyOf(eventId: String): String? {
    val invite = deviceJson("invite", "event" to eventId)["invite"]!!.jsonPrimitive.content
    return (decodeEventUrl(invite) as ConfigDecodeResult.Success).payload.key
}

suspend fun Rig.leave() {
    user("leave")
    awaitState { !it.ready.configResolved }
}

/** Add one own photo — a camera photo unless [kind] says otherwise. */
suspend fun Rig.addPhoto(id: String, date: String = Rig.PHOTO_DATE, kind: LibraryPhotoKind = LibraryPhotoKind.PHOTO) {
    library.add(id, kind, date)
}

/** The own photo's asset in the library, by id. */
suspend fun Rig.asset(id: String, resources: Boolean = false): AssetView? =
    gallery(resources).policy?.assets?.firstOrNull { it.assetId == id }

suspend fun Rig.permission(grant: PhotoGrant) {
    library.permission(grant)
}

/** Wait until an import the operator asked to suspend has parked in the library; answers it. */
suspend fun Rig.awaitImportParked(): ParkedImport = eventually(read = { library.imports().parked }) { it != null }!!
