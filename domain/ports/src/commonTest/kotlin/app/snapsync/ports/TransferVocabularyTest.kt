package app.snapsync.ports

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** The transfer ports' own values, as the upload services read them: a case, and the facts it carries. */
class TransferVocabularyTest {

    @Test
    fun `a change was applied or refused with the platform's code`() {
        val answers = listOf(ChangeOutcome.Applied, ChangeOutcome.Refused(3311, "denied")).map {
            when (it) {
                ChangeOutcome.Applied -> "Applied"
                is ChangeOutcome.Refused -> "Refused"
            }
        }
        assertEquals(listOf("Applied", "Refused"), answers)
        assertEquals(ChangeOutcome.Refused(3311, "denied"), ChangeOutcome.Refused(3311, "denied"))
        assertNotEquals(ChangeOutcome.Refused(3311, "denied"), ChangeOutcome.Refused(null, "denied"))
    }

    @Test
    fun `a failed transfer is one fact compared by what the platform said`() {
        assertEquals(TransferFailure("http:500"), TransferFailure("http:500"))
        assertNotEquals(TransferFailure("http:500"), TransferFailure("network"))
    }
}
