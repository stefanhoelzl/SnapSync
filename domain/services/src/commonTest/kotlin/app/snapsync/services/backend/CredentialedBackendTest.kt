package app.snapsync.services.backend

import app.snapsync.model.Reply
import app.snapsync.model.VersionRefusal
import app.snapsync.services.version.AppVersionGate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

/**
 * The decisions the HTTP interceptor used to make, now in the authenticated backend (capability `privacy-security`,
 * "Only a rejected credential is invalidated, and only that one"; capability `app-update-required`): which token a
 * call carries, what a `401` means and what is done about it, and what the version gate learns.
 */
class CredentialedBackendTest {

    private val unauthorized = Reply.Refused(401, "invalid token")

    @Test
    fun the_token_is_read_per_call_never_held() = runTest {
        val credential = ScriptedCredential("T1")
        val backend = ScriptedBackend()
        val authenticated = CredentialedBackend(backend, credential, versionGate = null)

        authenticated.joinEvent("E", "D")
        credential.current = "T2"
        authenticated.joinEvent("E", "D")

        assertEquals(listOf("join T1", "join T2"), backend.calls, "a renewal in the background is picked up by the next call")
    }

    @Test
    fun a_missing_token_still_sends_the_call_and_its_401_is_no_verdict_on_any_credential() = runTest {
        val credential = ScriptedCredential(null, recovered = "T2")
        val backend = ScriptedBackend { _, _ -> unauthorized }

        val reply = CredentialedBackend(backend, credential, versionGate = null).joinEvent("E", "D")

        assertEquals(unauthorized, reply)
        assertEquals(listOf("join null"), backend.calls, "refusing to send would strand the work with nothing to retry")
        assertEquals(emptyList(), credential.rejections, "a call that carried no token learns nothing about one")
    }

    /**
     * The declared behaviour change: a call refused for its token is sent ONCE more with the token the recovery
     * obtained, so the caller is answered by the retry rather than told "failed" for a request the next attempt
     * would have served.
     */
    @Test
    fun a_rejected_token_is_reported_by_value_and_the_call_retried_once_with_the_recovered_one() = runTest {
        val credential = ScriptedCredential("T1", recovered = "T2")
        val backend = ScriptedBackend { _, token -> if (token == "T1") unauthorized else Reply.Ok(Unit) }

        val reply = CredentialedBackend(backend, credential, versionGate = null).putDeviceConfig("D", app.snapsync.model.ApnsPushToken("t", "e"))

        assertEquals(Reply.Ok(Unit), reply)
        assertEquals(listOf("T1"), credential.rejections, "the rejection names the token the call CARRIED")
        assertEquals(listOf("config T1", "config T2"), backend.calls)
    }

    @Test
    fun only_once_a_second_rejection_is_the_calls_answer() = runTest {
        val credential = ScriptedCredential("T1", recovered = "T2")
        val backend = ScriptedBackend { _, _ -> unauthorized }

        val reply = CredentialedBackend(backend, credential, versionGate = null).createEvent(app.snapsync.model.CreateEventRequest("n", "s", null))

        assertEquals(unauthorized, reply)
        assertEquals(listOf("create T1", "create T2"), backend.calls, "one retry, never a loop")
        assertEquals(listOf("T1"), credential.rejections, "the retry's refusal is not reported again")
    }

    @Test
    fun no_retry_when_the_credential_has_nothing_better_to_send() = runTest {
        // The extension's credential: it drops the rejected token and offers none.
        val dropsOnly = ScriptedCredential("T1", recovered = null)
        val backend = ScriptedBackend { _, _ -> unauthorized }
        assertEquals(unauthorized, CredentialedBackend(backend, dropsOnly, versionGate = null).publishManifest("E", "D", app.snapsync.model.DeviceManifest("D", emptyList())))
        assertEquals(listOf("manifest T1"), backend.calls)

        // A recovery that answers the very token that was rejected is no reason to send it again.
        val same = ScriptedCredential("T1", recovered = "T1")
        val again = ScriptedBackend { _, _ -> unauthorized }
        CredentialedBackend(again, same, versionGate = null).leaveEvent("E", "D")
        assertEquals(listOf("leave T1"), again.calls)
    }

    @Test
    fun a_transport_failure_or_another_refusal_is_never_mistaken_for_a_rejected_credential() = runTest {
        val credential = ScriptedCredential("T1", recovered = "T2")
        val offline = Reply.Unreachable(IllegalStateException("offline"))
        CredentialedBackend(ScriptedBackend { _, _ -> offline }, credential, versionGate = null).deviceFiles("D")
        CredentialedBackend(ScriptedBackend { _, _ -> Reply.Refused(403, "no") }, credential, versionGate = null).renameEvent("E", "n")
        assertEquals(emptyList(), credential.rejections, "dropping a good credential on a blip costs a throttled re-attestation")
    }

    @Test
    fun the_public_reads_carry_no_token_and_are_never_retried() = runTest {
        val credential = ScriptedCredential("T1", recovered = "T2")
        val backend = ScriptedBackend { _, _ -> unauthorized }
        val authenticated = CredentialedBackend(backend, credential, versionGate = null)

        authenticated.getEvent("E")
        authenticated.eventFiles("E")

        assertEquals(listOf("get null", "union null"), backend.calls)
        assertEquals(emptyList(), credential.rejections)
        assertEquals(0, credential.reads, "an ungated read never reads the credential")
    }

    @Test
    fun a_426_on_any_route_refuses_this_build_and_any_success_clears_it() = runTest {
        val gate = AppVersionGate()
        var reply: Reply<*> = Reply.Refused(426, """{"error":"app too old","minAppVersion":"0.5"}""")
        val authenticated = CredentialedBackend(ScriptedBackend { _, _ -> reply }, ScriptedCredential("T1"), gate)

        authenticated.getEvent("E")
        assertEquals(VersionRefusal("0.5"), gate.refusal.value, "the ungated read learns it too — the gate precedes every route")

        reply = Reply.Refused(404, "not found")
        authenticated.joinEvent("E", "D")
        assertEquals(VersionRefusal("0.5"), gate.refusal.value, "an answer that is not success says nothing about this build")

        reply = Reply.Ok(Unit)
        authenticated.joinEvent("E", "D")
        assertNull(gate.refusal.value, "a served call heals the screen — nothing else has to remember to")
    }

    @Test
    fun a_refusal_on_the_retry_reaches_the_version_gate_too() = runTest {
        val gate = AppVersionGate()
        val backend = ScriptedBackend { _, token ->
            if (token == "T1") unauthorized else Reply.Refused(426, """{"minAppVersion":"0.6"}""")
        }
        val reply = CredentialedBackend(backend, ScriptedCredential("T1", recovered = "T2"), gate).joinEvent("E", "D")
        assertIs<Reply.Refused>(reply)
        assertEquals(VersionRefusal("0.6"), gate.refusal.value)
    }
}
