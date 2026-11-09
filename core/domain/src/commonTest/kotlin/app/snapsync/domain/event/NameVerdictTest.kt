package app.snapsync.domain.event

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** What a name, as read, comes to (decision record `changes/seal-event-name`, D6). */
class NameVerdictTest {

    private val known = (EventName.of("Ana's 3oth") as EventNameVerdict.Ok).name

    @Test
    fun `a readable or opened name is the event's name, under the name rule`() {
        assertEquals(
            "Ana's 30th",
            assertIs<NameVerdict.Named>(NameVerdict.of(NameRead.Text(" Ana's 30th "), null)).name.text,
        )
        assertEquals(
            "Ana's 30th",
            assertIs<NameVerdict.Named>(NameVerdict.of(NameRead.Text("Ana's 30th"), known)).name.text,
            "what the server serves wins over what this device knew",
        )
        assertEquals(NameVerdict.Unreadable, NameVerdict.of(NameRead.Text("   "), known), "a blank name is no answer")
        assertEquals(NameVerdict.Unreadable, NameVerdict.of(NameRead.Text("x".repeat(101)), known), "nor a long one")
    }

    @Test
    fun `a record the event's own key does not open is a failed load, whatever was known`() {
        assertEquals(NameVerdict.Unreadable, NameVerdict.of(NameRead.Damaged, known))
        assertEquals(NameVerdict.Unreadable, NameVerdict.of(NameRead.Damaged, null))
    }

    @Test
    fun `without the key the last name known stands, and with none the name is withheld`() {
        assertEquals(known, assertIs<NameVerdict.Named>(NameVerdict.of(NameRead.NoKey, known)).name)
        assertEquals(NameVerdict.Withheld, NameVerdict.of(NameRead.NoKey, null))
    }

    @Test
    fun `a served name is the readable one, else the sealed record, else neither`() {
        assertEquals("plain", assertIs<ServedName.Readable>(ServedName.of("plain", "rec")).text)
        assertEquals("rec", assertIs<ServedName.Sealed>(ServedName.of(null, "rec")).record)
        assertEquals(ServedName.Neither, ServedName.of(null, null))
    }
}
