package app.snapsync.integration

import app.snapsync.model.JoinPhase
import app.snapsync.model.Layer
import app.snapsync.model.ShareCount
import app.snapsync.model.SyncHealth
import app.snapsync.rig.RigState
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

// The marketing screenshots' states (`docs/deployment.md`, "Screenshots"), each reached the way a person reaches it:
// the world seeded through the mocks' operator faces, the app driven through the intents a tap produces. So every
// committed raw is a screen the real reduction reached. `ShotsTest` runs them on the JVM host on every build;
// `:test:integration:screenshots` runs them against the rig build on a simulator and captures each (`screenshots.yml`).

/** One marketing screenshot: its file name's stem, and the screen it settles on. */
enum class Shot(val id: String, val settled: (RigState) -> Boolean) {
    /** The landing screen: no event yet, the create form seeded from the clock. */
    CREATE("create", { it.ui.layer is Layer.CreateEvent }),

    /** The join confirmation a scanned QR opens: the event loaded, the member's photos counted, ready to confirm. */
    JOINING("joining", { s ->
        val gate = s.ui.layer as? Layer.JoiningEvent
        (gate?.phase as? JoinPhase.Detailed)?.step == JoinPhase.Detailed.Step.Ready &&
            (gate.range?.shareCount as? ShareCount.Ready)?.count == Shot.OWN_PHOTOS
    }),

    /** Joined and settled: every own photo shared, every other member's received. */
    IN_SYNC("in_sync", { it.health == SyncHealth.InSync && it.download.let { d -> d.total > 0 && d.downloaded == d.total } }),
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
    }
}

/**
 * Drive this host's app, from an empty world, to [shot]'s screen, and answer once it has settled there. [relaunch] is
 * the app's process death and next launch over the same mocked systems: the create form reads the clock once, when it
 * first appears, so the clock is set before the launch that shows anything.
 */
suspend fun Rig.reach(shot: Shot, relaunch: suspend Rig.() -> Unit): RigState {
    // The clock last: a launch-adapters host saves every changed system together, so the clock saved means both were.
    permission("granted")
    device("clock/advance", "to" to Shot.NOW)
    relaunch()
    when (shot) {
        Shot.CREATE -> Unit
        Shot.JOINING -> {
            takePhotos()
            val event = registerEvent(Shot.EVENT_NAME, Shot.EVENT_START, Shot.EVENT_END)
            openLink(inviteLink(event))
        }
        Shot.IN_SYNC -> {
            takePhotos()
            device("backend/next-event-id", "id" to Shot.EVENT_ID)
            createAndJoin(name = Shot.EVENT_NAME, startsAt = Shot.EVENT_START, endsAt = Shot.EVENT_END)
            uploadAll()
            foreignDevice("GUEST", *Array(Shot.THEIR_PHOTOS) { "GUEST-${it + 1}" })
            downloadAll()
            foreground()
        }
    }
    return awaitState(until = shot.settled)
}

/** This device's photos of the event, one every twenty minutes from its start. */
private suspend fun Rig.takePhotos() {
    val start = Instant.parse(Shot.EVENT_START + "Z")
    repeat(Shot.OWN_PHOTOS) { i -> addPhoto("IMG-${i + 1}", date = (start + (20 * (i + 1)).minutes).toString()) }
}
