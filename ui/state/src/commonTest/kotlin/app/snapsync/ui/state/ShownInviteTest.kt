@file:OptIn(MintsShownInvite::class, ShowsInvite::class)

package app.snapsync.ui.state

import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** The invite the screen shows: on the wire as the plain text it always was, printed with no key. */
class ShownInviteTest {
    private val text = "https://snapsync.stho.net/join/11111111-1111-4111-8111-111111111111#k=AAECAwQFBgcICQ"
    private val invite = ShownInvite(text)

    @Test
    fun `it travels as its plain text and reads back whole`() {
        val encoded = Json.encodeToString(ShownInvite.serializer(), invite)
        assertEquals(Json.encodeToString(String.serializer(), text), encoded)
        assertEquals(text, Json.decodeFromString(ShownInvite.serializer(), encoded).raw)
    }

    @Test
    fun `printed - and printed inside the screen's state - it carries no key`() {
        assertFalse("AAECAwQFBgcICQ" in invite.toString())
        assertFalse("AAECAwQFBgcICQ" in listOf(invite).toString())
    }
}
