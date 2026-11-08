@file:OptIn(MintsInviteText::class, RendersInvite::class)

package app.snapsync.domain.invite

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** An invite's text: read whole by what shows or shares it, printed with no key. */
class InviteTextTest {
    private val text = "https://snapsync.stho.net/join/11111111-1111-4111-8111-111111111111#k=AAECAwQFBgcICQ"

    @Test
    fun `what shows or shares it reads it whole`() = assertEquals(text, InviteText(text).raw)

    @Test
    fun `printed it carries no key - nor any of its text`() {
        assertEquals("InviteText(‹invite›)", InviteText(text).toString())
        assertFalse("AAECAwQFBgcICQ" in "${InviteText(text)}")
    }
}
