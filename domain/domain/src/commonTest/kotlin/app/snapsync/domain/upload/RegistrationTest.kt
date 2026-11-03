package app.snapsync.domain.upload

import app.snapsync.domain.upload.RegistrationVerdict.Companion.ACCESS_USER_DENIED
import app.snapsync.domain.upload.RegistrationVerdict.Companion.IDENTIFIER_NOT_FOUND
import app.snapsync.domain.upload.RegistrationWrite.DISABLE
import app.snapsync.domain.upload.RegistrationWrite.ENABLE
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(MintsFullGrant::class)
class RegistrationTest {

    @Test
    fun `a write that took is applied, and one no platform carries is unsupported`() {
        RegistrationWrite.entries.forEach { write ->
            assertEquals(RegistrationVerdict.Applied(write), RegistrationVerdict.of(write, RegistrationReply.Took))
            assertEquals(
                RegistrationVerdict.Unsupported(write),
                RegistrationVerdict.of(write, RegistrationReply.NotCarried),
            )
        }
    }

    @Test
    fun `a disable that found no record, and a refusal under a partial grant, are named apart from a failure`() {
        assertEquals(
            RegistrationVerdict.NothingToDisable,
            RegistrationVerdict.of(DISABLE, RegistrationReply.Refused(IDENTIFIER_NOT_FOUND)),
        )
        assertEquals(
            RegistrationVerdict.DisableRefusedByGrant,
            RegistrationVerdict.of(DISABLE, RegistrationReply.Refused(ACCESS_USER_DENIED)),
        )
        assertEquals(
            RegistrationVerdict.EnableRefusedByGrant,
            RegistrationVerdict.of(ENABLE, RegistrationReply.Refused(ACCESS_USER_DENIED)),
        )
    }

    @Test
    fun `every other refusal is a failure - an enable that finds no record included`() {
        assertEquals(
            RegistrationVerdict.Failed(ENABLE, 3202),
            RegistrationVerdict.of(ENABLE, RegistrationReply.Refused(3202)),
        )
        assertEquals(
            RegistrationVerdict.Failed(ENABLE, IDENTIFIER_NOT_FOUND),
            RegistrationVerdict.of(ENABLE, RegistrationReply.Refused(IDENTIFIER_NOT_FOUND)),
        )
        assertEquals(
            RegistrationVerdict.Failed(DISABLE, null),
            RegistrationVerdict.of(DISABLE, RegistrationReply.Refused(null)),
        )
        assertEquals(
            RegistrationVerdict.Failed(ENABLE, null),
            RegistrationVerdict.of(ENABLE, RegistrationReply.Refused(null)),
        )
        assertEquals(
            RegistrationVerdict.Failed(DISABLE, 3202),
            RegistrationVerdict.of(DISABLE, RegistrationReply.Refused(3202)),
        )
    }

    @Test
    fun `a full grant is minted by its read alone`() {
        FullGrant()
    }
}
