package app.snapsync.application

import app.snapsync.application.trust.RefreshAttestation
import app.snapsync.application.trust.RefreshOutcome
import app.snapsync.data.FakeClient
import app.snapsync.data.Held
import app.snapsync.data.Subject
import app.snapsync.data.token
import app.snapsync.domain.trust.DeviceRefusal
import app.snapsync.domain.trust.TokenIssuance
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The wake's refresh: what it came to, as the entry point that calls it reads it. */
@OptIn(EntryPointCall::class)
class RefreshAttestationTest {
    @Test
    fun each_refresh_is_answered_as_what_it_came_to() = runTest {
        assertEquals(RefreshOutcome.Fresh, RefreshAttestation(Subject(held = Held(token(29), "k")).credential)())
        assertEquals(RefreshOutcome.Obtained, RefreshAttestation(Subject().credential)())

        val refused = Subject()
        refused.client.mintAnswers += TokenIssuance.Refused(DeviceRefusal.DEVICE_MODIFIED, detail = null)
        assertEquals(
            RefreshOutcome.NotObtained(DeviceRefusal.DEVICE_MODIFIED),
            RefreshAttestation(refused.credential)(),
        )

        assertEquals(
            RefreshOutcome.NotObtained(refusal = null),
            RefreshAttestation(Subject(client = FakeClient(challenge = null)).credential)(),
            "no answer is no verdict",
        )
    }
}
