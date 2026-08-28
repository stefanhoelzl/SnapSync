package app.snapsync.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

/**
 * The date vocabulary (capability `photo-selection-policy`). Two properties carry the whole design:
 * the wrapping must not disturb **ordering** (every capture-date compare in the system is a plain string
 * compare that is only chronological because the shape is canonical), and it must not disturb the
 * **wire** (the persisted `EventConfig` is the only record of a join, so a changed encoding strands every
 * joined device).
 *
 * The role-fencing itself is not tested here because it cannot be: a role confusion is a **compile**
 * error, so a test asserting it would not build. `SelectionPolicyContainmentTest` covers the part the
 * types cannot.
 */
class EventDatesTest {

    private val json = Json

    @Test
    fun `lexicographic order is chronological for the canonical shape`() {
        val earlier = CaptureDate("2026-07-06T14:32:11Z")
        val later = CaptureDate("2026-07-06T14:32:12Z")
        assertTrue(earlier < later)
        assertTrue(later > earlier)
        assertEquals(earlier, CaptureDate("2026-07-06T14:32:11Z"))
        // The undated asset: sorts before every real date, so any real cutoff excludes it.
        assertTrue(CaptureDate("") < earlier)
    }

    @Test
    fun `ordering survives the role wrapping`() {
        // The clamps are `maxOf`/`minOf` over these, so a role type that compared by anything other than
        // its underlying instant would silently move a bound.
        assertTrue(captureCutoff("2026-01-01T00:00:00Z") < captureCutoff("2026-06-01T00:00:00Z"))
        assertTrue(captureCeiling("2026-06-01T00:00:00Z") > captureCeiling("2026-01-01T00:00:00Z"))
        assertTrue(eventStart("2026-01-01T00:00:00Z") < eventStart("2026-06-01T00:00:00Z"))
        assertTrue(eventEnd("2026-06-01T00:00:00Z") > eventEnd("2026-01-01T00:00:00Z"))
        assertTrue(deletesAt("2026-06-01T00:00:00Z") > deletesAt("2026-01-01T00:00:00Z"))
    }

    @Test
    fun `every role serializes as its bare canonical string`() {
        // THE compatibility property. `EventConfig` is the only record of a join and the invite QR is
        // derived from it, so an encoding that wrapped these in an object would strand every joined
        // device permanently — with nothing in the app to surface the event id back.
        assertEquals("\"2026-07-06T14:32:11Z\"", json.encodeToString(CaptureDate("2026-07-06T14:32:11Z")))
        assertEquals("\"2026-07-06T14:32:11Z\"", json.encodeToString(captureCutoff("2026-07-06T14:32:11Z")))
        assertEquals("\"2026-07-06T14:32:11Z\"", json.encodeToString(captureCeiling("2026-07-06T14:32:11Z")))
        assertEquals("\"2026-07-06T14:32:11Z\"", json.encodeToString(eventStart("2026-07-06T14:32:11Z")))
        assertEquals("\"2026-07-06T14:32:11Z\"", json.encodeToString(eventEnd("2026-07-06T14:32:11Z")))
        assertEquals("\"2026-07-06T14:32:11Z\"", json.encodeToString(deletesAt("2026-07-06T14:32:11Z")))
    }

    @Test
    fun `a config written before the vocabulary existed still decodes`() {
        // The exact bytes an already-joined device has on disk: bare strings for every date.
        val persisted = """
            {"eventId":"11111111-1111-4111-8111-111111111111","name":"Birthday",
             "minPhotoDate":"2026-07-06T14:32:11Z","startsAt":"2026-07-01T09:00:00Z",
             "endsAt":"2026-07-08T09:00:00Z","maxPhotoDate":"2026-07-08T09:00:00Z",
             "deletesAt":"2026-07-31T09:00:00Z"}
        """.trimIndent()
        val decoded = json.decodeFromString(EventConfig.serializer(), persisted)

        assertEquals(captureCutoff("2026-07-06T14:32:11Z"), decoded.minPhotoDate)
        assertEquals(eventStart("2026-07-01T09:00:00Z"), decoded.startsAt)
        assertEquals(eventEnd("2026-07-08T09:00:00Z"), decoded.endsAt)
        assertEquals(captureCeiling("2026-07-08T09:00:00Z"), decoded.maxPhotoDate)
        assertEquals(deletesAt("2026-07-31T09:00:00Z"), decoded.deletesAt)

        // …and re-encoding it produces the same bytes, so a round-trip through this build is inert.
        assertEquals(
            json.parseToJsonElement(persisted),
            json.parseToJsonElement(json.encodeToString(EventConfig.serializer(), decoded)),
        )
    }

    @Test
    fun `a millisecond-bearing instant is modelled apart from the canonical roles`() {
        // `createdAt` is minted by the backend's `toISOString()` and carries milliseconds, which sort
        // BEFORE the same instant without them ('.' is 0x2E, 'Z' is 0x5A). That is the dangerous
        // direction: a millisecond-bearing value fed to the floor's `maxOf` clamp reads as EARLIER and
        // therefore loses, silently lowering the capture floor and admitting photos the member excluded.
        // Mixing the two shapes in one lexicographic compare is wrong in a way no test using round
        // instants would ever show — hence a separate type that cannot reach a capture-date compare.
        assertTrue("2026-07-06T14:32:11.182Z" < "2026-07-06T14:32:11Z")
        assertEquals("2026-07-06T14:32:11.182Z", MillisInstant("2026-07-06T14:32:11.182Z").iso)
    }
}
