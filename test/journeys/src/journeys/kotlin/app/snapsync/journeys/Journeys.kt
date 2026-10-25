package app.snapsync.journeys

import app.snapsync.client.RigClient
import app.snapsync.control.Choices
import app.snapsync.control.Verifies
import app.snapsync.control.person
import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.invite.InviteLinkVerdict
import app.snapsync.domain.sharing.Direction
import app.snapsync.format.InviteLinkText
import app.snapsync.levers.DevControlsLevers
import app.snapsync.levers.GalleryLevers
import app.snapsync.levers.MovingFile
import app.snapsync.levers.MovingPhoto
import app.snapsync.levers.OsLevers
import app.snapsync.levers.RigLevers
import app.snapsync.levers.SeedKind
import app.snapsync.model.ResourceRole
import app.snapsync.ui.state.DirectionCount
import app.snapsync.ui.state.JoinPhase
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.SyncCounts
import app.snapsync.ui.state.UiState
import app.snapsync.wire.api.Limits
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.plus
import kotlinx.datetime.todayIn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/**
 * The all-real journeys (`docs/testing.md`, "All-real journeys are the contracts' safety net"): the
 * rig build of the app on ONE simulator or emulator — member A — and the real backend served locally, driven through the
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
 * ONE test, five journeys in order, because each stands on the last and the simulator is shared: A creates and
 * joins; A's photos land in the backend and the event's union; a member joins A's event through the id in A's invite
 * link, shares photos, and they arrive in A's library; the member shares photos that MOVE — an iPhone Live Photo and
 * real Android motion photos — and each arrives moving, in the receiving phone's own form; and A's own moving photo is
 * shared whole. The moving photos are real camera files: the Live Photo an SE2 took (`moving/`, committed) and five
 * phones' motion photos (`motionphoto/`, fetched by the build from Immich's test assets — `build.gradle.kts` says why).
 *
 * The same journeys run on iOS and Android, by the device runs' program (`journeys`, this module's `main`), which waits
 * on what the screen shows and then reads the real system once: the backend's union, the photo library.
 */
class Journeys {

    private val appA = address("appA")
    private val backend = address("backend").trimEnd('/')

    @Test
    @Verifies(
        spec = "create-event",
        requirement = "The creator joins through the same join screen as every guest",
        scenario = "A created event opens its join screen",
    )
    @Verifies(
        spec = "receiving-photos",
        requirement = "Other members' photos arrive in the photo library automatically",
        scenario = "A co-member's photo appears in the library",
    )
    @Verifies(
        spec = "receiving-photos",
        requirement = "Received photos keep full fidelity",
        scenario = "A Live Photo stays live",
    )
    @Verifies(
        spec = "receiving-photos",
        requirement = "Received photos keep full fidelity",
        scenario = "An Android motion photo reaches iPhone as a Live Photo",
    )
    @Verifies(
        spec = "receiving-photos",
        requirement = "Received photos keep full fidelity",
        scenario = "A Live Photo reaches Android as a motion photo",
    )
    @Verifies(
        spec = "receiving-photos",
        requirement = "Received photos keep full fidelity",
        scenario = "A motion photo stays moving between Android phones",
    )
    @Verifies(
        spec = "delivery",
        requirement = "Only the original is uploaded, complete",
        scenario = "A Live Photo travels whole",
    )
    @Verifies(
        spec = "delivery",
        requirement = "Only the original is uploaded, complete",
        scenario = "An Android motion photo travels as one file",
    )
    fun aMemberCreatesSharesAndReceivesAnotherMembersPhotos() = runBlocking {
        RigClient(appA).use { a ->
            // Never drive an app baked for another backend: that is the shared production one, and these journeys
            // create events and upload photos.
            val base = a.state().build["uploadBase"]
            assertTrue(base == backend, "the app under journey is baked for '$base', not the local backend $backend")
            HttpClient(CIO).use { http ->
                val event = createAndJoin(a)
                shareOwnPhotos(a, http, event)
                val member = Member(http, backend)
                receive(a, member)
                receiveMovingPhotos(a, member)
                shareOwnMovingPhotos(a, http, event, member)
            }
        }
    }

    /** Journey 1 — create an event, confirm the join it opens, and reach the joined screen. */
    private suspend fun createAndJoin(a: RigClient): String {
        // The contracts ran on this app first and leave photos behind; the durable sync state is cleared so the
        // journey starts unjoined.
        a.levers(DevControlsLevers).reset()
        val today = Clock.System.todayIn(TimeZone.currentSystemDefault())
        // Today through three days out: contains the seeded photos, which are dated an hour ahead of now.
        val startsAt = today.plus(-1, DateTimeUnit.DAY).atTime(LocalTime(0, 0))
        val endsAt = today.plus(3, DateTimeUnit.DAY).atTime(LocalTime(0, 0))
        a.person().createEvent("Journey", local(startsAt), local(endsAt))
        val gate = a.awaitScreen(JOIN, "the created event's join screen, its details loaded") {
            (it.layer as? Layer.JoiningEvent)?.phase is JoinPhase.Detailed
        }
        val event = (gate.layer as Layer.JoiningEvent).eventId
        a.person().confirmJoin(Choices(direction = Direction.Both, saveToAlbum = false))
        a.awaitScreen(JOIN, "the joined screen of event $event") { joinedTo(it) == event }
        return event
    }

    /**
     * Journey 2 — A's own photos land: seeded photos, half above the resolution floor, uploaded by the app's own
     * uploader on the foreground entry, and served by the event's union.
     */
    private suspend fun shareOwnPhotos(a: RigClient, http: HttpClient, event: String) {
        a.levers(GalleryLevers).seed(4, SeedKind.POLICY)
        val cutoff = a.state().ready.minPhotoDate ?: fail("A's membership carries no cutoff")
        val admitted = a.levers(GalleryLevers).read(cutoff = cutoff).policy?.assets.orEmpty()
            .filter { it.admitted }.mapTo(mutableSetOf()) { AssetId(it.assetId) }
        assertTrue(admitted.size >= 2, "the policy seed admits its above-floor half: $admitted")

        a.levers(OsLevers).foreground()
        a.awaitScreen(UPLOAD, "A's ${admitted.size} admitted photos shared") { sharedCount(it).reached(admitted.size) }
        // Shared means stored: the backend serves them in the union from then on (one read, no retry — a union that
        // lags the count is a gap of its own, which a retry would hide).
        val union = unionAssetIds(http, event)
        assertTrue(
            union.containsAll(admitted),
            "A's shared photos in the event union: admitted $admitted, union $union",
        )
    }

    /**
     * Journey 3 — a member joins A's event through the id A's invite link carries, and shares real photos; they
     * arrive in A's library through the real download and PhotoKit import.
     */
    private suspend fun receive(a: RigClient, member: Member) {
        val invite = (a.state().ui.layer as? Layer.Joined)?.inviteUrl ?: fail("A's joined screen carries no invite link")
        val link = when (val read = InviteLinkText.parse(invite)) {
            is InviteLinkVerdict.Invite -> read.link
            is InviteLinkVerdict.KeyUnreadable, InviteLinkVerdict.NotAnInvite ->
                fail("A's invite link does not read ($read): $invite")
        }
        val event = link.event.value
        // An encrypted event's invite carries its key, and the member keeps it as a device does at its join.
        member.join(event, link.key?.text)
        // Captured today at noon: inside the event's window, as a member's photo of the event would be.
        val today = Clock.System.todayIn(TimeZone.UTC)
        val shared = member.share(event, count = MEMBER_PHOTOS, creationDate = "${today}T12:00:00Z")

        val before = a.levers(GalleryLevers).read(cutoff = WHOLE_LIBRARY).census.total
        val receivedBefore = receivedCount(a.state().ui).done
        a.levers(OsLevers).foreground()
        a.awaitScreen(DOWNLOAD, "the member's ${shared.size} photos received") {
            receivedCount(it).reached(receivedBefore + shared.size)
        }
        // Received means imported into the library: one read.
        val after = a.levers(GalleryLevers).read(cutoff = WHOLE_LIBRARY).census.total
        assertTrue(after >= before + shared.size, "the member's ${shared.size} photos in A's library: $before → $after")
    }

    /**
     * Journey 4 — the member shares photos that MOVE, as their phones saved them: an iPhone Live Photo (a HEIC still and
     * its movie, two resources) and five Android motion photos (one file each — Pixel 6 Pro, Pixel 8a with two video
     * tracks, Samsung One UI 5 and 6 with sound, and a One UI 6 HEIC). Every one must arrive MOVING in A's library, in A's
     * own form: a Live Photo on iPhone, a motion photo on Android. A photo that arrives as a still, or never arrives —
     * the SE2's stuck motion photo with sound — fails here.
     */
    private suspend fun receiveMovingPhotos(a: RigClient, member: Member) {
        val event = joinedTo(a.state().ui) ?: fail("A is not joined")
        val photos = listOf(LIVE_PHOTO.map(::part)) + MOTION_PHOTOS.map { listOf(part(it)) }
        val before = a.levers(GalleryLevers).read(cutoff = WHOLE_LIBRARY).census
        val movingBefore = before.moving ?: fail("this host does not count moving photos: ${a.levers(RigLevers).advertise().host}")
        val receivedBefore = receivedCount(a.state().ui).done
        val today = Clock.System.todayIn(TimeZone.UTC)
        member.share(event, photos, creationDate = "${today}T12:00:00Z")

        a.levers(OsLevers).foreground()
        a.awaitScreen(DOWNLOAD, "the member's ${photos.size} moving photos received") {
            receivedCount(it).reached(receivedBefore + photos.size)
        }
        val census = a.levers(GalleryLevers).read(cutoff = WHOLE_LIBRARY).census
        assertTrue(
            census.total >= before.total + photos.size && (census.moving ?: 0) >= movingBefore + photos.size,
            "the member's ${photos.size} moving photos moving in A's library: $before → $census",
        )
    }

    /**
     * Journey 5 — A's own moving photo is shared WHOLE: on iPhone the Live Photo, its still and its movie both; on Android
     * two motion photos, each as the one file the camera saved — added to A's library as its camera left them, dated
     * inside the event.
     */
    private suspend fun shareOwnMovingPhotos(a: RigClient, http: HttpClient, event: String, member: Member) {
        val photos = if (a.levers(RigLevers).advertise().host.startsWith("IOS")) {
            listOf(LIVE_PHOTO)
        } else {
            listOf(listOf("motionphoto/samsung-one-ui-6.jpg"), listOf("motionphoto/samsung-one-ui-6.heic"))
        }
        // An hour ahead, as the policy seed dates its photos: inside an event that started yesterday.
        val taken = (Clock.System.now() + 1.hours).toString()
        val sharedBefore = sharedCount(a.state().ui).done
        photos.forEach { files ->
            val body = MovingPhoto(taken, files.map { MovingFile(it.substringAfterLast('/'), base64(fixture(it))) })
            a.levers(GalleryLevers).addMoving(body)
        }

        a.levers(OsLevers).foreground()
        a.awaitScreen(UPLOAD, "A's ${photos.size} moving photo(s) shared") {
            sharedCount(it).reached(sharedBefore + photos.size)
        }
        val own = union(http, event).filter { it["deviceId"]?.jsonPrimitive?.content != member.deviceId }
        val whole = photos.all { files ->
            own.any { shapeOf(it) == files.map(::part).map { p -> p.role.wire to p.filename }.toSet() }
        }
        assertTrue(whole, "A's ${photos.size} moving photo(s) in the event union, whole: ${own.map(::shapeOf)}")
    }

    /** The (role, filename) pairs an asset of the union is made of. */
    private fun shapeOf(asset: JsonObject): Set<Pair<String, String>> =
        asset["resources"]?.jsonArray.orEmpty().mapTo(mutableSetOf()) {
            val resource = it.jsonObject
            resource.getValue("role").jsonPrimitive.content to resource.getValue("filename").jsonPrimitive.content
        }

    /** A fixture as the part of a photo its name makes it: a movie is a Live Photo's video, anything else the primary. */
    private fun part(path: String): Member.Part {
        val name = path.substringAfterLast('/')
        val (role, type) = when (name.substringAfterLast('.').lowercase()) {
            "mov" -> ResourceRole.LIVE to "video/quicktime"
            "heic" -> ResourceRole.PRIMARY to "image/heic"
            else -> ResourceRole.PRIMARY to "image/jpeg"
        }
        return Member.Part(role, type, name, fixture(path))
    }

    private fun fixture(path: String): ByteArray =
        checkNotNull(Journeys::class.java.getResourceAsStream("/$path")) {
            "the journeys' resource $path is missing — `motionphoto/` is fetched by :test:journeys:fetchMotionPhotos"
        }.use { it.readBytes() }

    private fun base64(bytes: ByteArray): String = java.util.Base64.getEncoder().encodeToString(bytes)

    /** The event's union — the backend's public surface, read as a member reads it. */
    private suspend fun union(http: HttpClient, event: String): List<JsonObject> {
        val body = http.get(
            "$backend/events/$event/files",
        ) { header(Limits.VERSION_HEADER, SERVED_VERSION) }.bodyAsText()
        return runCatching { Json.parseToJsonElement(body).jsonArray.map { it.jsonObject } }.getOrDefault(emptyList())
    }

    /** The asset ids the event's union serves — the backend's public surface, read as a member reads it. */
    private suspend fun unionAssetIds(http: HttpClient, event: String): Set<AssetId> =
        union(http, event).mapTo(mutableSetOf()) { AssetId(it.getValue("assetId").jsonPrimitive.content) }

    /** The event the joined screen shows, or `null` off it. */
    private fun joinedTo(ui: UiState): String? = (ui.layer as? Layer.Joined)?.membership?.eventId

    /** The joined screen's shared count — nothing counted where the screen shows none. */
    private fun sharedCount(ui: UiState): DirectionCount.Progress = counts(ui) { it.shared }

    /** The joined screen's received count — nothing counted where the screen shows none. */
    private fun receivedCount(ui: UiState): DirectionCount.Progress = counts(ui) { it.received }

    private fun counts(ui: UiState, direction: (SyncCounts) -> DirectionCount): DirectionCount.Progress =
        (ui.layer as? Layer.Joined)?.counts?.let(direction) as? DirectionCount.Progress ?: DirectionCount.Progress(0, 0)

    /** At least [n] through, and nothing left: the count the journey's act should end in. */
    private fun DirectionCount.Progress.reached(n: Int): Boolean = done >= n && complete

    private fun local(value: LocalDateTime): String = value.toString()

    private companion object {
        val JOIN = 60.seconds
        val UPLOAD = 180.seconds
        val DOWNLOAD = 180.seconds
        const val SERVED_VERSION = "99.0"
        const val WHOLE_LIBRARY = "1970-01-01T00:00:00Z"
        const val MEMBER_PHOTOS = 2

        /** The SE2's own Live Photo: its still and its movie, paired by the content identifier the camera wrote. */
        val LIVE_PHOTO = listOf("moving/IMG_5927.HEIC", "moving/IMG_5927.MOV")

        /** Real Android motion photos, one file each. */
        val MOTION_PHOTOS = listOf(
            "pixel-6-pro.jpg",
            "pixel-8a.jpg",
            "samsung-one-ui-5.jpg",
            "samsung-one-ui-6.jpg",
            "samsung-one-ui-6.heic",
        ).map { "motionphoto/$it" }

        fun address(name: String): String = System.getProperty("snapsync.journey.$name")
            ?: fail("snapsync.journey.$name is not set — the journeys run only against the addresses their script passes")
    }
}
