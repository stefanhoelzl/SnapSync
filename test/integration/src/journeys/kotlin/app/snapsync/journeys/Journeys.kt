package app.snapsync.journeys

import app.snapsync.control.RigClient
import app.snapsync.control.done
import app.snapsync.model.APP_VERSION_HEADER
import app.snapsync.model.normalizeAssetId
import app.snapsync.presentation.JoinPhase
import app.snapsync.presentation.Layer
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.time.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.plus
import kotlinx.datetime.todayIn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The all-real journeys (capability `testing-architecture`, "All-real journeys are the contracts' safety net"): the
 * rig build of the iOS app on two simulators — members A and B — and the real backend served locally, driven
 * through the same typed client every integration test speaks.
 *
 * They exist because the integration surface runs on mocks the port contracts license, so a real run tests the
 * CONTRACTS: a failure here that the mocked suite does not show is read first as a clause nobody wrote, and fixed by
 * writing it.
 *
 * ONE test, three journeys in order, because each stands on the last and the simulators are shared: A creates and
 * joins; A's photos land in the backend and the event's union; B joins A's event download-only and receives them.
 */
class Journeys {

    private val appA = address("appA")
    private val appB = address("appB")
    private val backend = address("backend").trimEnd('/')

    @Test
    fun a_member_creates_shares_and_a_second_member_receives() = runBlocking {
        RigClient(appA).use { a ->
            RigClient(appB).use { b ->
                HttpClient(CIO).use { http ->
                    val event = createAndJoin(a)
                    val shared = shareOwnPhotos(a, http, event)
                    receive(a, b, shared)
                }
            }
        }
    }

    /** Journey 1 — create an event, confirm the join it opens, and reach the joined screen. */
    private suspend fun createAndJoin(a: RigClient): String {
        // The contracts ran on this app first and leave photos behind; the durable sync state is cleared so the
        // journey starts unjoined.
        a.deviceVerb("reset").done()
        val today = Clock.System.todayIn(TimeZone.currentSystemDefault())
        // Today through three days out: contains the seeded photos, which are dated an hour ahead of now.
        val startsAt = today.plus(-1, DateTimeUnit.DAY).atTime(LocalTime(0, 0))
        val endsAt = today.plus(3, DateTimeUnit.DAY).atTime(LocalTime(0, 0))
        a.user("create", mapOf("name" to "Journey", "startsAt" to local(startsAt), "endsAt" to local(endsAt))).done()
        val gate = a.awaitState(JOIN) { (it.ui.layer as? Layer.JoiningEvent)?.phase is JoinPhase.Detailed }
        val event = (gate.ui.layer as Layer.JoiningEvent).eventId
        a.user("confirmJoin", mapOf("direction" to "both", "saveToAlbum" to "false")).done()
        val joined = a.awaitState(JOIN) { it.ready.configResolved }
        assertTrue(joined.ui.layer is Layer.Joined, "A reached the joined screen: ${joined.ui}")
        assertTrue(joined.ready.eventId == event)
        return event
    }

    /**
     * Journey 2 — A's own photos land: seeded photos, half above the resolution floor, uploaded by the app's own
     * uploader on the foreground entry, and served by the event's union. Answers the asset ids that must arrive.
     */
    private suspend fun shareOwnPhotos(a: RigClient, http: HttpClient, event: String): Set<String> {
        a.deviceVerb("gallery/seed", mapOf("n" to "4", "kind" to "policy")).done()
        val cutoff = a.state().ready.minPhotoDate ?: fail("A's membership carries no cutoff")
        val admitted = a.gallery(cutoff = cutoff).policy?.assets.orEmpty()
            .filter { it.admitted }.mapTo(mutableSetOf()) { normalizeAssetId(it.assetId) }
        assertTrue(admitted.size >= 2, "the policy seed admits its above-floor half: $admitted")

        a.os("app", "onForeground").done()
        eventually(UPLOAD, "A's photos in the event union") {
            unionAssetIds(http, event).containsAll(admitted)
        }
        return admitted
    }

    /** Journey 3 — B joins A's event download-only through A's invite link, and A's photos arrive in B's library. */
    private suspend fun receive(a: RigClient, b: RigClient, shared: Set<String>) {
        b.deviceVerb("reset").done()
        val before = b.gallery(cutoff = WHOLE_LIBRARY).census.total
        val invite = a.state().inviteUrl ?: fail("A's joined screen carries no invite link")
        b.os("app", "onSceneContinueActivity", arg = invite).done()
        b.awaitState(JOIN) { (it.ui.layer as? Layer.JoiningEvent)?.phase is JoinPhase.Detailed }
        b.user("confirmJoin", mapOf("direction" to "download", "saveToAlbum" to "false")).done()
        b.awaitState(JOIN) { it.ready.configResolved }

        b.os("app", "onForeground").done()
        val received = b.awaitState(DOWNLOAD) { it.download.total >= shared.size && it.download.downloaded == it.download.total }
        assertTrue(received.download.downloaded >= shared.size, "B downloaded A's photos: ${received.download}")
        eventually(DOWNLOAD, "A's photos in B's library") {
            b.gallery(cutoff = WHOLE_LIBRARY).census.total >= before + shared.size
        }
    }

    /** The asset ids the event's union serves — the backend's public surface, read as a member reads it. */
    private suspend fun unionAssetIds(http: HttpClient, event: String): Set<String> {
        val body = http.get("$backend/events/$event/files") { header(APP_VERSION_HEADER, SERVED_VERSION) }.bodyAsText()
        return runCatching {
            Json.parseToJsonElement(body).jsonArray
                .mapTo(mutableSetOf()) { normalizeAssetId(it.jsonObject.getValue("assetId").jsonPrimitive.content) }
        }.getOrDefault(emptySet())
    }

    private suspend fun eventually(timeout: Duration, what: String, condition: suspend () -> Boolean) {
        val deadline = kotlin.time.TimeSource.Monotonic.markNow() + timeout
        while (!condition()) {
            if (deadline.hasPassedNow()) fail("$what: not within $timeout")
            delay(POLL)
        }
    }

    private fun local(value: LocalDateTime): String = value.toString()

    private companion object {
        val JOIN = 60.seconds
        val UPLOAD = 180.seconds
        val DOWNLOAD = 180.seconds
        val POLL = 1.seconds
        const val SERVED_VERSION = "99.0"
        const val WHOLE_LIBRARY = "1970-01-01T00:00:00Z"

        fun address(name: String): String = System.getProperty("snapsync.journey.$name")
            ?: fail("snapsync.journey.$name is not set — the journeys run only against the addresses ios-contracts passes")
    }
}
