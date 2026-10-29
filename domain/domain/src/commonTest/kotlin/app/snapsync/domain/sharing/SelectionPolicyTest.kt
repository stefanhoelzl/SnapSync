package app.snapsync.domain.sharing

import app.snapsync.domain.asset.AssetFacts
import app.snapsync.domain.asset.CaptureDate
import app.snapsync.domain.asset.MediaKind
import app.snapsync.domain.asset.RecordedAsset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The one policy (capability `photo-sharing`): what a member contributes, decided for a read and for ledger rows. */
class SelectionPolicyTest {

    private val now = ts("2026-06-03T00:00:00Z")

    private fun SelectionPolicy.admitted(vararg assets: AssetFacts, selected: Boolean = false): List<String> {
        val set = when (selected) {
            true -> CandidateSet.Selected(assets.toList(), now)
            false -> CandidateSet.Walked(assets.toList(), now)
        }
        return assertIs<AdmissionVerdict.Discovered>(admit(set)).discovery.admitted.map { it.facts.assetId.value }
    }

    @Test
    fun `a member who shares contributes their range, which a walk is narrowed to`() {
        val policy = policy()
        assertTrue(policy.contributes)
        assertEquals(WalkBounds.Within(RANGE), policy.walkBounds)
    }

    @Test
    fun `a member who does not share contributes nothing and reads nothing`() {
        for (direction in listOf(Direction.DownloadOnly, Direction.Neither)) {
            val policy = policy(direction)
            assertFalse(policy.contributes)
            assertEquals(WalkBounds.None, policy.walkBounds)
            assertEquals(emptyList(), policy.admitted(asset("CAM")))
            assertEquals(
                emptyList(),
                policy.admitRecorded(listOf(RecordedAsset(id("CAM"), dated("2026-06-02T10:00:00Z")))),
            )
        }
    }

    @Test
    fun `a walk of the library admits only what is certainly an event photo, inside the range`() {
        val admitted = policy(received = setOf(id("ECHO")), denied = setOf(id("WA"))).admitted(
            asset("CAM"),
            asset("SHOT", kind = MediaKind.SCREENSHOT),
            asset("REC", kind = MediaKind.SCREEN_RECORDING),
            asset("SMALL", pixels = 1_920_000),
            asset("CROP", pixels = 1_920_000, edited = true),
            asset("UNKNOWN", pixels = null),
            asset("CLIP", kind = MediaKind.VIDEO, pixels = 1920L * 1080L),
            asset("NARROW", kind = MediaKind.VIDEO, pixels = 1100L * 900L),
            asset("TINY_CLIP", kind = MediaKind.VIDEO, pixels = 640L * 480L),
            asset("BEFORE", taken = "2026-05-31T23:59:59Z"),
            asset("AFTER", taken = "2026-06-07T00:00:01Z"),
            asset("UNDATED", taken = null),
            asset("ECHO"),
            asset("WA"),
        )
        assertEquals(listOf("CAM", "CROP", "UNKNOWN", "CLIP", "NARROW"), admitted)
    }

    @Test
    fun `a selection is judged by the range and echo suppression alone, the member chose each photo`() {
        val admitted = policy(received = setOf(id("ECHO")), denied = setOf(id("WA"))).admitted(
            asset("SHOT", kind = MediaKind.SCREENSHOT),
            asset("REC", kind = MediaKind.SCREEN_RECORDING),
            asset("SMALL", pixels = 1_920_000),
            asset("WA"),
            asset("BEFORE", taken = "2026-05-31T23:59:59Z"),
            asset("UNDATED", taken = null),
            asset("ECHO"),
            selected = true,
        )
        assertEquals(listOf("SHOT", "REC", "SMALL", "WA"), admitted)
    }

    @Test
    fun `a discovery says when its read began, where its photos came from and how many it considered`() {
        val walked = assertIs<AdmissionVerdict.Discovered>(
            policy().admit(CandidateSet.Walked(listOf(asset("CAM"), asset("SHOT", kind = MediaKind.SCREENSHOT)), now)),
        ).discovery
        assertEquals(now, walked.takenAt)
        assertEquals(DiscoverySource.LIBRARY, walked.source)
        assertEquals(2, walked.considered)
        val selected = assertIs<AdmissionVerdict.Discovered>(policy().admit(CandidateSet.Selected(emptyList(), now)))
        assertEquals(DiscoverySource.SELECTION, selected.discovery.source)
        assertEquals(0, selected.discovery.considered)
    }

    @Test
    fun `a read that could not be made admits nothing and is no discovery`() {
        assertEquals(AdmissionVerdict.NotReadable, policy().admit(CandidateSet.NotReadable))
    }

    @Test
    fun `a ledger row is asked again, by the rules that need no more than its id and date`() {
        val rows = listOf(
            RecordedAsset(id("KEPT"), dated("2026-06-02T10:00:00Z")),
            RecordedAsset(id("NARROWED"), dated("2026-05-30T10:00:00Z")),
            RecordedAsset(id("UNDATED"), CaptureDate.Undated),
            RecordedAsset(id("ECHO"), dated("2026-06-02T10:00:00Z")),
            RecordedAsset(id("WA"), dated("2026-06-02T10:00:00Z")),
        )
        val admitted = policy(received = setOf(id("ECHO")), denied = setOf(id("WA"))).admitRecorded(rows)
        assertEquals(listOf(id("KEPT")), admitted.map { it.assetId })
    }

    @Test
    fun `a range is shared from the event's start while the start lies ahead`() {
        val early = range("2026-05-30T00:00:00Z", "2026-06-05T00:00:00Z")
        val before = ts("2026-05-31T00:00:00Z")
        val shared = early.sharedAt(before, START)
        assertEquals(START.at, shared.from.at, "nothing is shared before the event starts")
        assertEquals(early.until, shared.until)
        assertEquals(early, early.sharedAt(START.at, START), "from the start on, the whole range")
        assertEquals(RANGE, RANGE.sharedAt(before, START), "a range from the start is already shared from it")
    }

    @Test
    fun `a range holds the dates from its start to its end, and no undated photo`() {
        assertTrue(RANGE.holds(dated("2026-06-01T00:00:00Z")))
        assertTrue(RANGE.holds(dated("2026-06-07T00:00:00Z")))
        assertFalse(RANGE.holds(dated("2026-06-07T00:00:01Z")))
        assertFalse(RANGE.holds(CaptureDate.Undated))
    }

    @Test
    fun `each rule says whether it judges what a photo is`() {
        val origin = listOf(
            NoScreenshots,
            NoScreenRecordings,
            ImageFloor(SelectionCalibration.STANDARD.imageFloor),
            VideoFloor(SelectionCalibration.STANDARD.videoFloor),
            NotInDenylistedAlbum(emptySet()),
        )
        assertTrue(origin.all { it.judgesWhatItIs })
        assertTrue(listOf(DenyAll, InRange(RANGE), NotEcho(emptySet())).none { it.judgesWhatItIs })
    }

    @Test
    fun `the floors apply to their own kind only`() {
        val image = ImageFloor(SelectionCalibration.STANDARD.imageFloor)
        val video = VideoFloor(SelectionCalibration.STANDARD.videoFloor)
        assertTrue(image.admits(asset("CLIP", kind = MediaKind.VIDEO, pixels = 100)))
        assertTrue(video.admits(asset("SMALL", pixels = 100)))
        assertTrue(video.admits(asset("EDITED_CLIP", kind = MediaKind.VIDEO, pixels = 100, edited = true)))
        assertFalse(video.admits(asset("TINY_CLIP", kind = MediaKind.VIDEO, pixels = 100)))
    }

    @Test
    fun `the calibration denies an album by its exact title, ignoring case and surrounding spaces`() {
        val calibration = SelectionCalibration.STANDARD
        assertTrue(calibration.denies("whatsapp"))
        assertTrue(calibration.denies("  WhatsApp "))
        assertTrue(calibration.denies("Screenshots"))
        assertFalse(calibration.denies("WhatsApp Backup"))
        assertFalse(calibration.denies("Signal Hill Hike"))
        assertEquals(3_000_000L, calibration.imageFloor.pixels)
        assertEquals(1280L * 720L, calibration.videoFloor.pixels)
        assertTrue("Telegram" in calibration.denylist)
    }

    @Test
    fun `the grant decides the read before anything is read`() {
        assertEquals(ReadPlan.Walk, ReadPlan.of(PhotoAccess.FULL, selection = null as String?))
        assertEquals(
            "picked",
            assertIs<ReadPlan.Snapshot<String>>(ReadPlan.of(PhotoAccess.LIMITED, "picked")).selection,
        )
        assertEquals(ReadPlan.NotReadable, ReadPlan.of(PhotoAccess.LIMITED, null as String?), "unread is not empty")
        assertEquals(ReadPlan.NotReadable, ReadPlan.of(PhotoAccess.NONE, "picked"))
    }

    @Test
    fun `the album denylist is read only for a walk of a library a member shares from`() {
        assertTrue(ReadPlan.Walk.readsAlbums(Direction.Both))
        assertFalse(ReadPlan.Walk.readsAlbums(Direction.DownloadOnly))
        assertFalse(ReadPlan.Snapshot("picked").readsAlbums(Direction.Both))
        assertFalse(ReadPlan.NotReadable.readsAlbums(Direction.UploadOnly))
    }

    private fun dated(text: String): CaptureDate = CaptureDate.Dated(ts(text))
}
