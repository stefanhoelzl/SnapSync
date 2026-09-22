package app.snapsync.model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [changesManifestProjection] — the in-memory stores' copy of the SQLite triggers' rule for when the manifest
 * version advances (capability `sync-ledger`). Every projected field must count, and the two unprojected ones
 * must not: a field missed here makes a fake advance less often than the device, and a green suite hides it.
 */
class ManifestProjectionChangeTest {

    private val base = LedgerEntry(
        key = "A-primary.jpg",
        assetId = "A",
        state = LedgerState.DISCOVERED,
        creationDate = "2026-08-28T10:00:00Z",
        role = ResourceRole.PRIMARY,
        contentType = "image/jpeg",
        originalFilename = "IMG_0042.JPG",
        destinationPath = null,
    )

    private fun with(
        key: String = base.key,
        assetId: String = base.assetId,
        state: LedgerState = base.state,
        creationDate: String = base.creationDate,
        role: ResourceRole? = base.role,
        contentType: String = base.contentType,
        originalFilename: String = base.originalFilename,
        destinationPath: String? = base.destinationPath,
    ) = LedgerEntry(key, assetId, state, creationDate, role, contentType, originalFilename, destinationPath)

    @Test
    fun an_insert_and_a_delete_change_the_projection() {
        assertTrue(changesManifestProjection(null, base))
        assertTrue(changesManifestProjection(base, null))
    }

    @Test
    fun nothing_to_nothing_changes_nothing() {
        assertFalse(changesManifestProjection(null, null))
    }

    @Test
    fun every_projected_field_changes_the_projection() {
        val variants = mapOf(
            "key" to with(key = "B-primary.jpg"),
            "assetId" to with(assetId = "B"),
            "creationDate" to with(creationDate = "2026-08-29T10:00:00Z"),
            "role" to with(role = ResourceRole.LIVE),
            "contentType" to with(contentType = "image/heic"),
            "originalFilename" to with(originalFilename = "IMG_0043.JPG"),
        )
        for ((field, variant) in variants) {
            assertTrue(changesManifestProjection(base, variant), "a change to `$field` must advance the version")
        }
    }

    @Test
    fun state_and_destination_do_not_change_the_projection() {
        // The manifest carries no upload state; a bump per finished upload would republish every cycle.
        assertFalse(changesManifestProjection(base, with(state = LedgerState.COMPLETED, destinationPath = "/d")))
        assertFalse(changesManifestProjection(base, with()))
    }
}
