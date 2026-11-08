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

    @Test
    fun `an object key names its role after its last hyphen, and a key that names none is a primary`() {
        assertEquals(ResourceRole.LIVE, ResourceRoleText.ofKey("IMG-0001-live.mov"))
        assertEquals(ResourceRole.PRIMARY, ResourceRoleText.ofKey("IMG-0001-primary.heic"))
        assertEquals(ResourceRole.PRIMARY, ResourceRoleText.ofKey("IMG_0001.HEIC"), "no key the uploader composes")
    }
}
