package app.snapsync.journeys

import app.snapsync.control.RigClient
import app.snapsync.control.done
import app.snapsync.model.APP_VERSION_HEADER
import app.snapsync.model.ConfigDecodeResult
import app.snapsync.model.decodeEventUrl
import app.snapsync.model.AssetId
import app.snapsync.model.JoinPhase
import app.snapsync.model.Layer
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
 * The all-real journeys (`docs/testing.md`, "All-real journeys are the contracts' safety net"): the
 * rig build of the iOS app on ONE simulator — member A — and the real backend served locally, driven through the
 * same typed client every integration test speaks. The second member is played by this test itself, over the
 * backend's public HTTP surface only, with real JPEG bytes ([Member]): to the backend it is a device, to A a foreign
 * member whose photos take the real download and PhotoKit import path. One simulator, because a second freshly
 * created one's first-boot work swamped the hosted runner (decision record:
 * `changes/archive/2026-09-25-one-simulator-journeys`).
 *
 * They exist because the integration surface runs on mocks the port contracts license, so a real run tests the
 * CONTRACTS: a failure here that the mocked suite does not show is read first as a clause nobody wrote, and fixed by
 * writing it.
 *
 * ONE test, three journeys in order, because each stands on the last and the simulator is shared: A creates and
 * joins; A's photos land in the backend and the event's union; a member joins A's event through the id in A's invite
 * link, shares photos, and they arrive in A's library.
 */
class Journeys {

    private val appA = address("appA")
    private val backend = address("backend").trimEnd('/')

    @Test
    fun a_member_creates_shares_and_receives_another_members_photos() = runBlocking {
        RigClient(appA).use { a ->
            // Never drive an app baked for another backend: that is the shared production one, and these journeys
            // create events and upload photos.
            val base = a.state().build["uploadBase"]
            assertTrue(base == backend, "the app under journey is baked for '$base', not the local backend $backend")
            HttpClient(CIO).use { http ->
                val event = createAndJoin(a)
                shareOwnPhotos(a, http, event)
                receive(a, Member(http, backend))
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
     * uploader on the foreground entry, and served by the event's union.
     */
    private suspend fun shareOwnPhotos(a: RigClient, http: HttpClient, event: String) {
        a.deviceVerb("gallery/seed", mapOf("n" to "4", "kind" to "policy")).done()
        val cutoff = a.state().ready.minPhotoDate ?: fail("A's membership carries no cutoff")
        val admitted = a.gallery(cutoff = cutoff).policy?.assets.orEmpty()
            .filter { it.admitted }.mapTo(mutableSetOf()) { AssetId(it.assetId) }
        assertTrue(admitted.size >= 2, "the policy seed admits its above-floor half: $admitted")

        a.os("app", "onForeground").done()
        eventually(UPLOAD, "A's photos in the event union") {
            unionAssetIds(http, event).containsAll(admitted)
        }
    }

    /**
     * Journey 3 — a member joins A's event through the id A's invite link carries, and shares real photos; they
     * arrive in A's library through the real download and PhotoKit import.
     */
    private suspend fun receive(a: RigClient, member: Member) {
        val invite = a.state().inviteUrl ?: fail("A's joined screen carries no invite link")
        val event = when (val link = decodeEventUrl(invite)) {
            is ConfigDecodeResult.Success -> link.payload.eventId
            is ConfigDecodeResult.Failure -> fail("A's invite link does not decode (${link.reason}): $invite")
        }
        member.join(event)
        // Captured today at noon: inside the event's window, as a member's photo of the event would be.
        val today = Clock.System.todayIn(TimeZone.UTC)
        val shared = member.share(event, count = MEMBER_PHOTOS, creationDate = "${today}T12:00:00Z")

        val before = a.gallery(cutoff = WHOLE_LIBRARY).census.total
        a.os("app", "onForeground").done()
        val received = a.awaitState(DOWNLOAD) {
            it.download.total >= shared.size && it.download.downloaded == it.download.total
        }
        assertTrue(
            received.download.downloaded >= shared.size,
            "A downloaded the member's photos: ${received.download}",
        )
        eventually(DOWNLOAD, "the member's photos in A's library") {
            a.gallery(cutoff = WHOLE_LIBRARY).census.total >= before + shared.size
        }
    }

    /** The asset ids the event's union serves — the backend's public surface, read as a member reads it. */
    private suspend fun unionAssetIds(http: HttpClient, event: String): Set<AssetId> {
        val body = http.get("$backend/events/$event/files") { header(APP_VERSION_HEADER, SERVED_VERSION) }.bodyAsText()
        return runCatching {
            Json.parseToJsonElement(body).jsonArray
                .mapTo(mutableSetOf()) { AssetId(it.jsonObject.getValue("assetId").jsonPrimitive.content) }
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
        const val MEMBER_PHOTOS = 2

        fun address(name: String): String = System.getProperty("snapsync.journey.$name")
            ?: fail("snapsync.journey.$name is not set — the journeys run only against the addresses ios-contracts passes")
    }
}
