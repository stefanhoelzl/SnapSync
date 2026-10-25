package app.snapsync.model

import app.snapsync.domain.asset.CaptureDate
import app.snapsync.domain.sharing.CaptureCeiling
import app.snapsync.domain.sharing.CaptureCutoff
import app.snapsync.format.TimestampText
import app.snapsync.testids.testAssetId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The origin-exclusion matrix (capability `photo-sharing`). Every rule is checked in both
 * directions — what it excludes *and* what it must not — because the expensive failure here is the false
 * drop: an event photo that silently never uploads.
 */
class SelectionPolicyTest {

    /** One asset, one primary resource, with the origin facts the enumerator would have carried across. */
    private fun asset(
        id: String,
        isScreenshot: Boolean = false,
        isScreenRecording: Boolean = false,
        isVideo: Boolean = false,
        width: Long = 4032,
        height: Long = 3024,
        adjusted: Boolean = false,
        mime: String = "image/heic",
    ) = listOf(
        Resource(
            filename = "$id-primary.heic",
            assetId = testAssetId(id),
            contentType = "public.heic",
            metadata = mapOf(
                RESOURCE_META_CREATION_DATE to "2026-07-01T00:00:00Z",
                RESOURCE_META_MIME to mime,
                RESOURCE_META_IS_SCREENSHOT to isScreenshot.toString(),
                RESOURCE_META_IS_SCREEN_RECORDING to isScreenRecording.toString(),
                RESOURCE_META_IS_VIDEO to isVideo.toString(),
                RESOURCE_META_IS_EDITED to adjusted.toString(),
                RESOURCE_META_PIXEL_AREA to (width * height).toString(),
            ),
            data = null,
        ),
    )

    /**
     * The ids the ORIGIN rules exclude. Expressed as "everything the one admission did not admit", over a
     * policy whose capture-date floor lies before every asset (admitting every date) — so this matrix isolates the origin
     * rules from the range, exactly as it did when they were a separate function.
     */
    private suspend fun excluded(resources: List<Resource>): Set<String> {
        val policy = SelectionPolicy(
            selectionRulesFor(
                includesUpload = true,
                cutoff = CaptureCutoff(TimestampText.parse("2000-01-01T00:00:00Z")),
                ceiling = null,
                suppressedAssetIds = { emptySet() },
                albumExcludedAssetIds = { emptySet() },
            ),
        )
        val admitted = EventPhotoSet(policy) { candidatesFromResources(resources) }
            .assets().mapTo(mutableSetOf()) { it.facts.assetId }
        return (resources.mapTo(mutableSetOf()) { it.assetId } - admitted).mapTo(mutableSetOf()) { it.value }
    }

    // ── The policy as a value ──

    @Test
    fun `two policies over the same rules are one value so a walk filtered by one is reused under the other`() {
        val floor = SelectionRule.CaptureAfter(CaptureCutoff(TimestampText.parse("2026-07-01T00:00:00Z")))
        val policy = { SelectionPolicy(listOf(floor, SelectionRule.ExcludeScreenshots)) }

        assertEquals(policy(), policy())
        assertEquals(policy().hashCode(), policy().hashCode())
        assertNotEquals(policy(), SelectionPolicy(listOf(floor)))
        assertNotEquals<Any>(policy(), listOf(floor, SelectionRule.ExcludeScreenshots))
    }

    @Test
    fun `the named-bounds door derives the same rules as the membership's`() = runTest {
        val cutoff = CaptureCutoff(TimestampText.parse("2026-06-01T00:00:00Z"))
        val ceiling = CaptureCeiling(TimestampText.parse("2026-06-30T00:00:00Z"))
        val named = selectionPolicyFor(
            includesUpload = true,
            cutoff = cutoff,
            ceiling = ceiling,
            suppressedAssetIds = { emptySet() },
            albumExcludedAssetIds = { emptySet() },
        )
        assertEquals(
            SelectionPolicy(selectionRulesFor(true, cutoff, ceiling, { emptySet() }, { emptySet() })),
            named,
        )
    }

    @Test
    fun `a policy contributes unless one of its rules refuses everything`() {
        assertTrue(SelectionPolicy(listOf(SelectionRule.ExcludeScreenshots, SelectionRule.MinImageArea(1))).contributes)
        assertFalse(SelectionPolicy(listOf(SelectionRule.ExcludeScreenshots, SelectionRule.DenyAll)).contributes)
    }

    // ── A partial grant's selection (capability `photo-sharing`, "the selection decides what a photo is") ──

    private val june = CaptureCutoff(TimestampText.parse("2026-06-01T00:00:00Z"))
    private val juneEnd = CaptureCeiling(TimestampText.parse("2026-06-30T00:00:00Z"))

    /** In range unless told otherwise; `ECHO` is a received photo and `CHAT` sits in a denylisted album. */
    private fun facts(
        id: String,
        at: String? = "2026-06-15T12:00:00Z",
        isScreenshot: Boolean = false,
        isScreenRecording: Boolean = false,
        isVideo: Boolean = false,
        pixelArea: Long? = 4032L * 3024L,
    ) = AssetFacts(
        testAssetId(id),
        captureDate = at?.let { CaptureDate.Dated(TimestampText.parse(it)) } ?: CaptureDate.Undated,
        isScreenshot = isScreenshot,
        isScreenRecording = isScreenRecording,
        isVideo = isVideo,
        pixelArea = pixelArea,
    )

    private suspend fun membershipPolicy(includesUpload: Boolean = true) = selectionPolicyFor(
        includesUpload = includesUpload,
        cutoff = june,
        ceiling = juneEnd,
        suppressedAssetIds = { setOf(testAssetId("ECHO")) },
        albumExcludedAssetIds = { setOf(testAssetId("CHAT")) },
    )

    private val whatItIs = listOf(
        facts("SHOT", isScreenshot = true),
        facts("REC", isVideo = true, isScreenRecording = true),
        facts("SMALL", pixelArea = 1600L * 1200L),
        facts("SMALLVID", isVideo = true, pixelArea = 640L * 360L),
        facts("CHAT"),
    )

    @Test
    fun `a selected photo is not excluded for what it is`() = runTest {
        val policy = membershipPolicy()
        whatItIs.forEach { assertTrue(policy.over(CandidateScope.Selection).admits(it), it.assetId.value) }
    }

    @Test
    fun `a library photo is still excluded for what it is`() = runTest {
        val policy = membershipPolicy()
        assertEquals(policy, policy.over(CandidateScope.Library))
        whatItIs.forEach { assertFalse(policy.over(CandidateScope.Library).admits(it), it.assetId.value) }
    }

    @Test
    fun `a selection is still judged by the range, echo suppression and the direction`() = runTest {
        val selection = membershipPolicy().over(CandidateScope.Selection)
        assertFalse(selection.admits(facts("BEFORE", at = "2026-05-31T23:59:59Z")), "before the range")
        assertFalse(selection.admits(facts("AFTER", at = "2026-07-01T00:00:01Z")), "after the range")
        assertFalse(selection.admits(facts("UNDATED", at = null)), "undated")
        assertFalse(selection.admits(facts("ECHO")), "a received photo")
        assertTrue(selection.admits(facts("CAMERA")))
        assertFalse(membershipPolicy(includesUpload = false).over(CandidateScope.Selection).contributes, "receive only")
    }

    @Test
    fun `every rule says whether it judges what a photo is`() = runTest {
        val judging = membershipPolicy().rules.filter { it.judgesWhatItIs }.map { it::class.simpleName }.toSet()
        assertEquals(
            setOf(
                "ExcludeScreenshots",
                "ExcludeScreenRecordings",
                "MinImageArea",
                "MinVideoArea",
                "NotInDenylistedAlbum",
            ),
            judging,
        )
        assertFalse(SelectionRule.DenyAll.judgesWhatItIs)
    }

    // ── Subtypes ──────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun screenshot_is_excluded() = runTest {
        assertEquals(setOf("A"), excluded(asset("A", isScreenshot = true)))
    }

    @Test
    fun screen_recording_is_excluded() = runTest {
        assertEquals(setOf("A"), excluded(asset("A", isScreenRecording = true, isVideo = true)))
    }

    @Test
    fun a_camera_photo_with_no_exclusion_flag_is_admitted() = runTest {
        // The other half of every rule: what it must NOT drop. Panoramas, HDR, Live Photos and depth
        // captures all arrive here as plain camera facts — the iOS adapter is what decides which subtype
        // bits mean "screenshot"/"screen recording", and `PhotoKitAssetFactsTest` pins that mapping where
        // the SDK constants can actually be verified.
        assertTrue(excluded(asset("A")).isEmpty())
    }

    // ── Animated images ───────────────────────────────────────────────────────────────────────────────
    // There is no GIF RULE any more (it was the one rule needing a resource read to decide, and the only
    // reason two consumers of "the admitted set" could disagree). Ordinary GIFs are still excluded — by
    // the resolution floor — and these tests pin that, so the removal is never mistaken for "GIFs upload
    // freely now".

    @Test
    fun an_ordinary_gif_is_still_excluded_by_the_resolution_floor() = runTest {
        // A messenger / Giphy GIF: 480x270 = 0.13 MP, three orders of magnitude below the 3 MP floor.
        assertEquals(
            setOf("A"),
            excluded(asset("A", width = 480, height = 270, mime = "image/gif")),
            "the floor catches it — no rule reads its MIME",
        )
    }

    @Test
    fun a_large_or_edited_gif_is_now_admitted_and_that_is_the_accepted_trade() = runTest {
        // The recall the removal costs, stated explicitly rather than left implicit. An EDITED gif skips
        // the floor (`hasAdjustments`), and a >=3 MP one clears it. Both are rare, and both land on the
        // admit-on-doubt side: a stray meme is visible and deletable, a silently-dropped event photo is
        // neither.
        assertTrue(excluded(asset("EDITED", width = 480, height = 270, adjusted = true, mime = "image/gif")).isEmpty())
        assertTrue(excluded(asset("BIG", width = 2048, height = 1536, mime = "image/gif")).isEmpty())
    }

    // ── Resolution floors ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun a_compressed_received_image_is_excluded() = runTest {
        // WhatsApp's 1600-long-edge cap → 1600x1200 = 1.92 MP, under the 3 MP floor.
        assertEquals(setOf("A"), excluded(asset("A", width = 1600, height = 1200)))
    }

    @Test
    fun a_camera_photo_is_admitted() = runTest {
        assertTrue(excluded(asset("A", width = 4032, height = 3024)).isEmpty())
    }

    @Test
    fun a_cropped_camera_photo_below_the_floor_is_admitted() = runTest {
        // The `hasAdjustments` guard. Without it, every heavy crop would silently vanish from the event.
        assertTrue(excluded(asset("A", width = 1000, height = 800, adjusted = true)).isEmpty())
    }

    @Test
    fun a_1080p_recording_is_admitted() = runTest {
        // THE load-bearing case: 1920x1080 = 2.07 MP is BELOW the 3 MP image floor. A single shared floor
        // would silently drop every 1080p video — and 1080p is the iOS capture default.
        assertTrue(excluded(asset("A", isVideo = true, width = 1920, height = 1080)).isEmpty())
    }

    @Test
    fun a_compressed_received_video_is_excluded() = runTest {
        assertEquals(setOf("A"), excluded(asset("A", isVideo = true, width = 848, height = 480)))
    }

    // ── Admit on doubt ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun an_asset_with_unknown_dimensions_is_admitted() = runTest {
        // Missing facts must never condemn an asset. A zero/absent dimension is "we don't know", not "tiny".
        assertTrue(excluded(asset("A", width = 0, height = 0)).isEmpty())
    }

    @Test
    fun an_asset_with_no_origin_metadata_at_all_is_admitted() = runTest {
        val bare = listOf(
            Resource(
                filename = "A-primary.heic",
                assetId = testAssetId("A"),
                contentType = "public.heic",
                metadata = mapOf(RESOURCE_META_CREATION_DATE to "2026-07-01T00:00:00Z"),
                data = null,
            ),
        )
        assertTrue(excluded(bare).isEmpty(), "absent facts admit — a fake or a legacy row must not vanish")
    }

    // ── Per-asset, not per-resource ───────────────────────────────────────────────────────────────────

    @Test
    fun an_excluded_asset_takes_all_of_its_resources_with_it() = runTest {
        // A live photo's primary + paired video. If the policy filtered per-resource on MIME, a GIF's
        // primary would be dropped while its sibling survived as an orphan whose bytes nothing uploads.
        val meta = mapOf(
            RESOURCE_META_CREATION_DATE to "2026-07-01T00:00:00Z",
            RESOURCE_META_IS_SCREENSHOT to "true",
            RESOURCE_META_PIXEL_AREA to "12192768",
        )
        val both = listOf(
            Resource("A-primary.heic", testAssetId("A"), "public.heic", metadata = meta, data = null),
            Resource("A-live.mov", testAssetId("A"), "com.apple.quicktime-movie", metadata = meta, data = null),
        )
        assertEquals(setOf("A"), excluded(both), "the asset is excluded, so both of its resources go")
    }

    @Test
    fun admitted_and_excluded_assets_are_separated_correctly() = runTest {
        val resources = asset("KEEP") + asset("SHOT", isScreenshot = true) +
            asset("SMALL", width = 800, height = 600) + asset("GIF", width = 480, height = 270, mime = "image/gif")
        assertEquals(setOf("SHOT", "SMALL", "GIF"), excluded(resources))
    }

    @Test
    fun `an undated asset is in no capture range and a resource with no stored date is undated`() {
        val undated = AssetFacts(testAssetId("U"), captureDate = CaptureDate.Undated)
        assertFalse(
            SelectionRule.CaptureAfter(CaptureCutoff(TimestampText.parse("2026-01-01T00:00:00Z"))).admits(undated),
        )
        assertFalse(
            SelectionRule.CaptureBefore(CaptureCeiling(TimestampText.parse("2026-12-31T00:00:00Z"))).admits(undated),
        )
        val resource = Resource("U-primary.jpg", testAssetId("U"), "image/jpeg", emptyMap(), null)
        assertEquals(CaptureDate.Undated, factsFromResources(listOf(resource)).single().captureDate)
    }
}
