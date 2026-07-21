package app.snapsync.model

import kotlin.test.Test
import kotlin.test.assertEquals

class RedactionTest {

    @Test
    fun replacesAPlainUuid() {
        assertEquals(
            "reconcile(eventId=‹uuid›)",
            redactUuids("reconcile(eventId=550e8400-e29b-41d4-a716-446655440000)"),
        )
    }

    @Test
    fun replacesUppercaseAndMixedCaseUuids() {
        assertEquals("‹uuid›", redactUuids("550E8400-E29B-41d4-A716-446655440000"))
    }

    @Test
    fun replacesUuidsEmbeddedInUrlsAndPaths() {
        assertEquals(
            "GET https://host/events/‹uuid›/devices/‹uuid›/manifest → 404",
            redactUuids(
                "GET https://host/events/550e8400-e29b-41d4-a716-446655440000" +
                    "/devices/123e4567-e89b-12d3-a456-426614174000/manifest → 404",
            ),
        )
    }

    @Test
    fun replacesEveryOccurrenceOnOneLine() {
        val line = "join 550e8400-e29b-41d4-a716-446655440000 as 123e4567-e89b-12d3-a456-426614174000"
        assertEquals("join ‹uuid› as ‹uuid›", redactUuids(line))
    }

    @Test
    fun leavesNearMissesIntact() {
        // Too-short group, non-hex characters, missing hyphen: none may be touched, because a
        // false positive would eat real diagnostic text (filenames, sizes, hashes).
        val nearMisses = listOf(
            "550e8400-e29b-41d4-a716-44665544000", // last group 11 chars
            "550e8400-e29b-41d4-a716-4466554400zz", // non-hex
            "550e8400e29b-41d4-a716-446655440000", // missing first hyphen
            "IMG_4021.HEIC 3024x4032",
        )
        for (text in nearMisses) assertEquals(text, redactUuids(text))
    }

    @Test
    fun redactsAUuidTouchingWordCharacters() {
        // Log lines interpolate without spaces ("id=<uuid>," or "…/<uuid>)"). The rule is
        // content-blind: even a UUID glued to other characters is scrubbed.
        assertEquals(
            "id=‹uuid›,next",
            redactUuids("id=550e8400-e29b-41d4-a716-446655440000,next"),
        )
    }

    @Test
    fun leavesUuidFreeTextUntouched(): Unit = assertEquals(
        "gallery: enumerated 12 resource(s) (3 origin-excluded) → N=9",
        redactUuids("gallery: enumerated 12 resource(s) (3 origin-excluded) → N=9"),
    )
}
