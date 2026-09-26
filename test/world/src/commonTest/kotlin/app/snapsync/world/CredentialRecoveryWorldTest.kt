package app.snapsync.world

import app.snapsync.model.ApnsPushToken
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The credential-recovery loop, composed (capability `privacy-security`, "Only a rejected credential is invalidated,
 * and only that one"): the backend rejects the token a gated call carried → the composed authenticated backend reports
 * it → the attestation service drops it and obtains a new one over the `/attest/…` routes → the SAME call is sent once
 * more with it and served.
 *
 * It used to need a text pin on the iOS shell (`CredentialRejectionWiringTest`), because the shell handed the core's
 * rejection hook to the HTTP client and nothing composed could reach that join. The credential now lives inside the
 * shared composition, over the Backend port every root supplies, so the world runs the phone's loop for real.
 */
class CredentialRecoveryWorldTest {

    @Test
    fun a_rejected_token_is_replaced_and_the_call_retried_once_with_the_new_one() = worldTest {
        val w = World(this, attests = true)
        w.core.attestation.refresh()
        val rejected = assertNotNull(w.core.attestation.token(), "the attesting world holds a token")

        w.store.refuseNextCredential = true
        val published = w.core.backend.pushTokens.publish(ApnsPushToken("tok", "sandbox"))

        assertTrue(published.isSuccess, "the call is answered by its retry: ${published.exceptionOrNull()}")
        val replacement = assertNotNull(w.core.attestation.token(), "recovery obtained a new token")
        assertNotEquals(rejected, replacement, "the rejected token is gone")
        assertEquals(2, w.registerPushCount, "the rejected attempt and its one retry — never a loop")
        assertNotNull(w.store.deviceConfigOf(w.ownDeviceId), "the retry is the write the backend stored")
    }

    @Test
    fun a_world_that_cannot_attest_is_answered_by_the_rejection() = worldTest {
        // The upload extension's position, and a simulator's: no integrity service, so nothing to recover with.
        val w = World(this, attests = false)
        w.store.refuseNextCredential = true

        w.core.backend.pushTokens.publish(ApnsPushToken("tok", "sandbox"))

        assertEquals(1, w.registerPushCount, "a call that carried no token is never retried")
    }
}
