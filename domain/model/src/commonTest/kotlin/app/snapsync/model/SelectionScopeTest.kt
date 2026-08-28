package app.snapsync.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

/**
 * What a member may upload **at all** under each grant (capability `limited-photo-access`): under a
 * partial grant the hand-picked selection IS the membership's own-photo scope, and discovery must read
 * exactly it and never walk.
 *
 * The rule was a `compose/` expression until this change. It is a rule about the vocabulary rather than
 * a wiring choice — get it wrong in the widening direction and a guest's whole camera roll reaches a
 * stranger's event, which is the inherited-default hazard the capability exists to close.
 */
class SelectionScopeTest {

    private fun snapshotOf(vararg ids: String) = ids.map {
        Resource(
            "$it-primary.jpg",
            it,
            "image/jpeg",
            mapOf(RESOURCE_META_CREATION_DATE to "2026-06-01T00:00:00Z"),
            Unit,
        )
    }

    @Test
    fun `LIMITED scopes discovery to exactly the snapshot`() {
        val snapshot = snapshotOf("S1", "S2")
        val scope = selectionScope(PermissionStatus.LIMITED, snapshot)
        assertIs<SelectionScope.Scoped>(scope)
        assertEquals(listOf("S1", "S2"), scope.resources.map { it.assetId })
    }

    @Test
    fun `LIMITED before the first snapshot scopes to nothing rather than widening`() {
        // THE LOAD-BEARING CASE. `null` is the gap between a grant turning partial and the first
        // observer emission. Collapsing it to Unrestricted would let discovery walk the whole library
        // under a grant whose entire point is that it may not.
        val scope = selectionScope(PermissionStatus.LIMITED, null)
        assertIs<SelectionScope.Scoped>(scope)
        assertEquals(emptyList(), scope.resources, "an unknown selection is an empty scope, not a free one")
    }

    @Test
    fun `LIMITED with an empty selection is scoped rather than unrestricted`() {
        assertIs<SelectionScope.Scoped>(selectionScope(PermissionStatus.LIMITED, emptyList()))
    }

    @Test
    fun `every other grant is unrestricted whatever the snapshot holds`() {
        // Total over the enum: only LIMITED scopes. DENIED / NOT_DETERMINED yield Unrestricted because
        // this value says what discovery MAY consult — refusing the read is the permission-aware
        // source's answer, not this one's — and a stale snapshot must not survive a grant widening.
        for (status in PermissionStatus.entries - PermissionStatus.LIMITED) {
            for (snapshot in listOf(null, emptyList(), snapshotOf("S1"))) {
                assertSame(
                    SelectionScope.Unrestricted, selectionScope(status, snapshot),
                    "$status must not scope discovery",
                )
            }
        }
    }
}
