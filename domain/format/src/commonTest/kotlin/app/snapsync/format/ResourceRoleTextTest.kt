package app.snapsync.format

import app.snapsync.domain.asset.ResourceRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A resource role's one text form: every stored object key, manifest and ledger row carries it, so a changed token
 * strands what an installed build uploaded.
 */
class ResourceRoleTextTest {

    @Test
    fun `each role has its token`() {
        assertEquals("primary", ResourceRoleText.of(ResourceRole.PRIMARY))
        assertEquals("live", ResourceRoleText.of(ResourceRole.LIVE))
    }

    @Test
    fun `a token reads back as its role, and any other text as none`() {
        ResourceRole.entries.forEach { assertEquals(it, ResourceRoleText.parse(ResourceRoleText.of(it))) }
        assertNull(ResourceRoleText.parse("PRIMARY"))
        assertNull(ResourceRoleText.parse(""))
    }
}
