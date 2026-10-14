@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.snapsync.data

import app.snapsync.data.identity.StoredDeviceIdentity
import app.snapsync.data.trust.PresentedChain
import app.snapsync.data.trust.RefreshRead
import app.snapsync.data.trust.RefusalFacts
import app.snapsync.data.version.AppVersionGate
import app.snapsync.domain.service.ServiceFault
import app.snapsync.domain.trust.DeviceRefusal
import app.snapsync.domain.trust.TokenIssuance
import app.snapsync.domain.version.VersionRefusal
import app.snapsync.mock.inMemorySecureStore
import app.snapsync.ports.AttestationChain
import app.snapsync.ports.CertificateFacts
import app.snapsync.ports.HttpResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The app's credential: obtaining, keeping alive and recovering the bearer token every gated request carries. */
private const val APP_TOO_OLD = """{"error":"app too old","minAppVersion":"9.9"}"""

class AttestingCredentialTest {

    // ---- attested: the SyncHealth.Unattested rule ----

    @Test
    fun `attested is true when the refresh obtains a token`() = runTest {
        val c = Subject()
        c.credential.refresh()
        assertTrue(c.status.attested.value)
    }

    @Test
    fun `attested is true for a usable token even when no refresh is possible`() = runTest {
        val c = Subject(held = Held(token(20), "k"))
        c.client.challenge = null // even with no network, a fresh token is a non-event
        c.credential.refresh()
        assertTrue(c.status.attested.value)
    }

    @Test
    fun `attested is false when there is no token at all and none can be obtained`() = runTest {
        val c = Subject()
        c.client.challenge = null
        c.credential.refresh()
        assertFalse(c.status.attested.value)
    }

    // The SNAPSYNC-20 correction: renewal fires a week early, and a token inside that margin still authorizes every
    // request. A renewal that fails then is no stall to surface.
    @Test
    fun `attested stays true for a token inside the renewal margin whose renewal fails`() = runTest {
        val c = Subject(held = Held(token(3), "k"))
        c.client.challenge = null // offline, exactly as on 2026-08-17
        assertIs<RefreshRead.NotObtained>(c.credential.refresh(), "the token IS due for renewal - that is why we tried")
        assertTrue(c.status.attested.value, "but it still authorizes every request, so nothing is surfaced")
    }

    @Test
    fun `attested is false for an expired token that could not be replaced`() = runTest {
        val c = Subject(held = Held(token(-1), "k"))
        c.client.challenge = null
        c.credential.refresh()
        assertFalse(c.status.attested.value)
        assertEquals(ServiceFault.UNREACHABLE, c.status.fault.value, "the service could not be reached")
    }

    @Test
    fun `every way a challenge goes unanswered is the service's condition - renewing or attesting`() = runTest {
        val answers = listOf(
            HttpResult.Response(502, emptyMap(), "upstream") to ServiceFault.TROUBLE,
            HttpResult.Response(503, mapOf("Retry-After" to "60"), "maintenance") to ServiceFault.UPDATING,
            HttpResult.Response(200, mapOf("Content-Type" to "text/html"), "<html>log in</html>") to ServiceFault.UNREACHABLE,
            HttpResult.Response(426, mapOf("Content-Type" to "application/json"), APP_TOO_OLD) to ServiceFault.UNREACHABLE,
            HttpResult.Unreachable("offline") to ServiceFault.UNREACHABLE,
        )
        for ((answer, fault) in answers) {
            for (held in listOf(Held(token(-1), "k"), Held())) {
                val c = Subject(held = held)
                c.client.challengeAnswer = answer
                c.credential.refresh()
                assertEquals(
                    fault,
                    c.status.fault.value,
                    "$answer, ${if (held.keyId() != null) "renewing" else "attesting"}",
                )
            }
        }
    }

    @Test
    fun `a refresh the service failed records how - and the next refresh forgets it`() = runTest {
        val c = Subject(held = Held(token(-1), "k"))
        c.client.challengeAnswer = HttpResult.Response(503, mapOf("Retry-After" to "60"), "maintenance")
        c.credential.refresh()
        assertEquals(ServiceFault.UPDATING, c.status.fault.value)

        c.client.challengeAnswer = null
        c.credential.refresh()
        assertNull(c.status.fault.value, "a refresh that obtained a token tells of no failure")
    }

    @Test
    fun `an unreadable token counts as unusable - never usable-until-proven-otherwise`() = runTest {
        val c = Subject(held = Held("not-a-token", "k"))
        c.client.challenge = null
        c.credential.refresh()
        assertFalse(c.status.attested.value)
    }

    @Test
    fun `narrowing what is SURFACED did not narrow when the app renews`() = runTest {
        val c = Subject(held = Held(token(3), "k"))
        c.credential.refresh()
        assertEquals(1, c.client.renewCalls, "a margin token must still renew at every wake")
        assertEquals(1, c.key.asserted)
    }

    @Test
    fun `a verdict is cleared when the next refresh BEGINS - not when it ends`() = runTest {
        val c = Subject(held = Held(token(-1), "k"))
        c.client.challenge = null
        c.credential.refresh()
        assertFalse(c.status.attested.value, "the offline wake concluded the device is stuck")

        val gate = CompletableDeferred<Unit>()
        c.client.challengeGate = gate
        val running = launch { c.credential.refresh() }
        runCurrent()
        assertTrue(c.status.attested.value, "the earlier wake's verdict is gone the moment this refresh starts")

        gate.complete(Unit) // still offline, so this attempt fails too
        running.join()
        assertFalse(c.status.attested.value, "and this attempt's own answer replaces it")
    }

    // ---- refusal: the service's verdict, told to the user ----

    @Test
    fun `a refused attestation publishes the reason the service named`() = runTest {
        val c = Subject()
        c.client.mintAnswers += TokenIssuance.Refused(DeviceRefusal.DEVICE_MODIFIED, detail = null)
        assertEquals(RefreshRead.NotObtained(DeviceRefusal.DEVICE_MODIFIED), c.credential.refresh())
        assertEquals(DeviceRefusal.DEVICE_MODIFIED, c.status.refusal.value)
        assertFalse(c.status.attested.value)
    }

    @Test
    fun `no answer is not a refusal`() = runTest {
        val c = Subject()
        c.client.challenge = null
        c.credential.refresh()
        assertNull(c.status.refusal.value)
        c.client.challenge = "chal"
        c.client.mintAnswers += TokenIssuance.NoAnswer(ServiceFault.UNREACHABLE)
        c.credential.refresh()
        assertNull(c.status.refusal.value, "a 5xx or a dropped connection is no verdict either")
    }

    @Test
    fun `a proof this device could not produce is not told as the service's refusal`() = runTest {
        val c = Subject(FakeKey(attestRefused = true))
        c.credential.refresh()
        assertNull(c.status.refusal.value)
        assertFalse(c.status.attested.value)
    }

    @Test
    fun `a refusal clears once the service verifies the phone`() = runTest {
        val c = Subject()
        c.client.mintAnswers += TokenIssuance.Refused(DeviceRefusal.DEVICE_UNVERIFIABLE, detail = null)
        c.credential.refresh()
        assertEquals(DeviceRefusal.DEVICE_UNVERIFIABLE, c.status.refusal.value)
        c.credential.refresh() // the next mint answers the scripted token
        assertNull(c.status.refusal.value)
        assertTrue(c.status.attested.value)
    }

    @Test
    fun `a refusal is cleared when the next refresh BEGINS - the SNAPSYNC-20 bracket`() = runTest {
        val c = Subject()
        c.client.mintAnswers += TokenIssuance.Refused(DeviceRefusal.APP_NOT_GENUINE, detail = null)
        c.credential.refresh()
        assertEquals(DeviceRefusal.APP_NOT_GENUINE, c.status.refusal.value)

        val gate = CompletableDeferred<Unit>()
        c.client.challengeGate = gate
        c.client.mintAnswers += TokenIssuance.Refused(DeviceRefusal.APP_NOT_GENUINE, detail = null)
        val running = launch { c.credential.refresh() }
        runCurrent()
        assertNull(c.status.refusal.value, "an earlier wake's refusal is gone while this attempt runs")

        gate.complete(Unit)
        running.join()
        assertEquals(DeviceRefusal.APP_NOT_GENUINE, c.status.refusal.value, "and this attempt's own answer replaces it")
    }

    @Test
    fun `a refresh that began before another publishes nothing over it`() = runTest {
        // A wake's refresh and a rejected call's recovery overlap. The earlier-begun one's verdict is outdated by the
        // time it ends, so only the later one's is shown: here the earlier is refused while the later is still running.
        val c = Subject()
        val first = CompletableDeferred<Unit>()
        c.client.challengeGate = first
        c.client.mintAnswers += TokenIssuance.Refused(DeviceRefusal.DEVICE_MODIFIED, detail = null)
        val earlier = launch { c.credential.refresh() }
        runCurrent()
        val later = launch { c.credential.refresh() }
        runCurrent()

        val second = CompletableDeferred<Unit>()
        c.client.challengeGate = second
        first.complete(Unit)
        earlier.join()
        runCurrent()
        assertNull(c.status.refusal.value, "the earlier refresh's refusal is not shown while the later one runs")
        assertTrue(c.status.attested.value)

        second.complete(Unit)
        later.join()
        assertTrue(c.status.attested.value, "the later refresh obtained a token, and says so")
    }

    @Test
    fun `a refusal keeps the service's answer and the chain the phone presented - until a success`() = runTest {
        val chain = AttestationChain(
            listOf(
                CertificateFacts(
                    "CN=Intermediate",
                    "CN=Root",
                    "2020-01-01T00:00:00Z",
                    "2030-01-01T00:00:00Z",
                    "EC 256",
                ),
            ),
            rootKeySha256 = "ab12",
        )
        val c = Subject(FakeKey(chain = chain))
        c.client.mintAnswers += TokenIssuance.Refused(DeviceRefusal.DEVICE_UNVERIFIABLE, detail = "certificate")
        c.credential.refresh()
        val facts = RefusalFacts(DeviceRefusal.DEVICE_UNVERIFIABLE, "certificate", PresentedChain.of(chain))
        assertEquals(facts, c.status.refusalFacts())

        // Not refusal's bracket: a refresh under way still finds them, for a report opened meanwhile.
        val gate = CompletableDeferred<Unit>()
        c.client.challengeGate = gate
        val running = launch { c.credential.refresh() }
        runCurrent()
        assertNull(c.status.refusal.value)
        assertEquals(facts, c.status.refusalFacts())
        gate.complete(Unit)
        running.join() // the next mint answers the scripted token
        assertNull(c.status.refusalFacts(), "a success clears them")
    }

    @Test
    fun `a request with no token attests now and answers the token - or the refusal`() = runTest {
        val c = Subject()
        c.client.mintAnswers += TokenIssuance.Refused(DeviceRefusal.DEVICE_MODIFIED, detail = null)
        assertNull(c.credential.missing().text())
        assertEquals(DeviceRefusal.DEVICE_MODIFIED, c.status.refusal.value, "the screen learns of it from this tap")

        assertEquals(token(30), c.credential.missing().text(), "a phone the service stopped refusing heals at once")
        assertNull(c.status.refusal.value)
    }

    // ---- obtaining and renewing ----

    @Test
    fun `a device that has never attested attests and keeps both the key and the token`() = runTest {
        val c = Subject()
        assertEquals(RefreshRead.Obtained, c.credential.refresh())
        assertEquals(1, c.key.attested)
        assertEquals(0, c.key.asserted) // nothing to assert WITH yet
        assertEquals(1, c.client.mintCalls)
        assertEquals("key-1", c.held.keyId())
        assertEquals(token(30), c.held.token())
    }

    @Test
    fun `an attested device RENEWS with an assertion - never a second attestation`() = runTest {
        // Apple attests a key ONCE; re-attesting is the throttled path. Were renewal to regress to it, it would have to
        // become rare, and rare renewal is the narrow window near expiry the design exists to avoid.
        val c = Subject(held = Held(token = token(1), keyId = "existing-key"))
        assertEquals(RefreshRead.Obtained, c.credential.refresh())
        assertEquals(1, c.key.asserted)
        assertEquals(0, c.key.attested)
        assertEquals(0, c.key.generated)
        assertEquals(1, c.client.renewCalls)
        assertEquals(token(30), c.held.token())
    }

    @Test
    fun `a fresh token is left alone - no challenge no network no Secure Enclave`() = runTest {
        val c = Subject(held = Held(token = token(29), keyId = "existing-key"))
        assertEquals(RefreshRead.Fresh, c.credential.refresh())
        assertEquals(0, c.key.asserted)
        assertEquals(0, c.key.attested)
        assertEquals(0, c.client.challengeCalls)
        assertEquals(token(29), c.held.token())
    }

    @Test
    fun `a refused renewal falls back to a full attestation rather than stalling forever`() = runTest {
        // The backend's record of this device can legitimately vanish — the leave cascade collects it.
        val c = Subject(client = FakeClient(renew = null), held = Held(token = token(1), keyId = "forgotten-key"))
        assertEquals(RefreshRead.Obtained, c.credential.refresh())
        assertEquals(1, c.key.asserted) // tried to renew…
        assertEquals(1, c.key.attested) // …then attested afresh
        assertEquals("key-1", c.held.keyId()) // and re-pointed at the NEW key
        assertEquals(token(30), c.held.token())
    }

    @Test
    fun `a renewal the backend has no record for attests afresh`() = runTest {
        val c = Subject(held = Held(token = token(1), keyId = "existing-key"))
        c.client.renewAnswers += TokenIssuance.NotAttested
        assertEquals(RefreshRead.Obtained, c.credential.refresh())
        assertEquals(1, c.key.attested, "the leave cascade removed the record: a full attestation replaces it")
        assertEquals(token(30), c.held.token())
    }

    @Test
    fun `a failed attestation keeps nothing`() = runTest {
        val c = Subject(FakeKey(attestRefused = true))
        assertIs<RefreshRead.NotObtained>(c.credential.refresh())
        assertNull(c.held.token())
        assertNull(c.held.keyId())
    }

    @Test
    fun `a renewal whose challenge went stale retries once with a fresh one and keeps the key`() = runTest {
        // B2: the app is suspended between fetching a challenge and using it. One fresh challenge is the answer — not a
        // new key on the throttled path, never a dropped token.
        val c = Subject(held = Held(token = token(1), keyId = "k"))
        c.client.renewAnswers += TokenIssuance.ChallengeStale
        assertEquals(RefreshRead.Obtained, c.credential.refresh())
        assertEquals(2, c.client.challengeCalls)
        assertEquals(2, c.client.renewCalls)
        assertEquals(0, c.key.attested, "a stale challenge is not a reason to attest afresh")
        assertEquals(token(30), c.held.token())
    }

    @Test
    fun `a renewal that stays stale or gets no answer keeps the token and attests nothing`() = runTest {
        for (answers in listOf(
            listOf(TokenIssuance.ChallengeStale, TokenIssuance.ChallengeStale),
            listOf(TokenIssuance.NoAnswer(ServiceFault.UNREACHABLE)),
        )) {
            val c = Subject(held = Held(token = token(1), keyId = "k"))
            c.client.renewAnswers += answers
            assertIs<RefreshRead.NotObtained>(c.credential.refresh(), "$answers")
            assertEquals(token(1), c.held.token(), "$answers must not touch the token the device holds")
            assertEquals(0, c.key.attested, "$answers says nothing about this device — no throttled attestation")
            assertEquals(0, c.client.mintCalls)
        }
    }

    @Test
    fun `an attestation whose challenge went stale retries once`() = runTest {
        val c = Subject()
        c.client.mintAnswers += TokenIssuance.ChallengeStale
        assertEquals(RefreshRead.Obtained, c.credential.refresh())
        assertEquals(2, c.client.mintCalls)
        assertEquals(token(30), c.held.token())
    }

    @Test
    fun `a backend that refuses the attestation leaves no key id behind`() = runTest {
        // A key id kept for an attestation the backend never recorded would send every renewal down the assertion path,
        // against a key the server has never heard of — a permanent 401 loop.
        val c = Subject(client = FakeClient(mint = null))
        assertIs<RefreshRead.NotObtained>(c.credential.refresh())
        assertNull(c.held.keyId())
        assertNull(c.held.token())
    }

    @Test
    fun `no challenge means the existing token is left in place - not discarded`() = runTest {
        val c = Subject(client = FakeClient(challenge = null), held = Held(token = token(1), keyId = "k"))
        assertIs<RefreshRead.NotObtained>(c.credential.refresh())
        assertEquals(token(1), c.held.token()) // still usable for a day — better than nothing
    }

    @Test
    fun `a process without App Attest is refused at the proof and mints nothing`() = runTest {
        val c = Subject(FakeKey(supported = false), held = Held(token = token(1)))
        assertIs<RefreshRead.NotObtained>(c.credential.refresh())
        assertEquals(1, c.key.attested, "the attestation was asked for — and refused")
        assertEquals(0, c.client.mintCalls)
        assertEquals(0, c.client.renewCalls)
        assertEquals(token(1), c.held.token())
    }

    @Test
    fun `an assertion the Secure Enclave cannot produce falls back to a full attestation`() = runTest {
        val c = Subject(FakeKey(assertRefused = true), held = Held(token = token(1), keyId = "k"))
        assertEquals(RefreshRead.Obtained, c.credential.refresh())
        assertEquals(0, c.client.renewCalls, "no request was sent for an assertion that was never produced")
        assertEquals(1, c.key.attested)
        assertEquals("key-1", c.held.keyId(), "the fresh key replaces the dead one")
    }

    @Test
    fun `the token is readable even when expired - the request goes out and the 401 is retried`() {
        // Refusing to hand out an expired token would build no request at all, which strands the resource.
        val c = Subject(held = Held(token = token(-5)))
        assertEquals(token(-5), c.credential.token().text())
    }

    @Test
    fun `concurrent refreshes collapse into ONE renewal`() = runTest {
        // Observed on device: a rejected token 401s every in-flight request, and each asked for a refresh — three
        // concurrent /attest/renew calls for one rejection, aimed at Apple's throttled path.
        val c = Subject(held = Held(token = token(1), keyId = "k"))
        val results = List(5) { async { c.credential.refresh() } }.awaitAll()
        assertEquals(1, results.count { it == RefreshRead.Obtained })
        assertEquals(4, results.count { it == RefreshRead.Fresh }, "those queued behind it find the token fresh")
        assertEquals(1, c.client.renewCalls)
        assertEquals(1, c.key.asserted)
    }

    @Test
    fun `obtaining a token announces it - so a refused registration can be re-sent`() = runTest {
        val minted = Subject()
        val mints = mutableListOf<Unit>()
        backgroundScope.launch { minted.status.tokenChanged.toList(mints) }
        runCurrent()
        minted.credential.refresh() // a fresh install ATTESTS
        runCurrent()
        assertEquals(1, mints.size)

        val renewed = Subject(held = Held(token = token(1), keyId = "k"))
        val renews = mutableListOf<Unit>()
        backgroundScope.launch { renewed.status.tokenChanged.toList(renews) }
        runCurrent()
        renewed.credential.refresh() // an attested device RENEWS
        runCurrent()
        assertEquals(1, renews.size)
    }

    // ---- what each process sees of the other's writes ----

    @Test
    fun `a token the extension dropped is seen at the next wake - and renewed`() = runTest {
        val c = Subject(client = FakeClient(renew = token(29)), held = Held(token = token(30), keyId = "k"))
        assertEquals(token(30), c.credential.token().text())

        c.held.dropElsewhere() // the extension, in its own process

        c.credential.refresh()
        assertEquals(1, c.client.renewCalls, "the refresh must see the drop, not the copy")
        assertEquals(token(29), c.credential.token().text())
        assertTrue(c.status.attested.value)
    }

    @Test
    fun `this process's own renewal is what the next request carries`() = runTest {
        val c = Subject(held = Held(token = token(1), keyId = "k"))
        assertEquals(token(1), c.credential.token().text())
        c.credential.refresh()
        assertEquals(token(30), c.credential.token().text())
    }

    // ---- recovering from a rejection ----

    @Test
    fun `a REJECTED token is dropped even though it is nowhere near expiry - and renewed with the key kept`() = runTest {
        // A token can be rejected while 30 days from expiry — the signing key rotated, or the leave cascade collected
        // this device's record. Treating rejected as fine re-sent the same dead credential forever.
        val c = Subject(
            client = FakeClient(renew = token(29)),
            held = Held(token = token(30), keyId = "still-good-key"),
        )
        assertEquals(token(29), c.credential.rejected(bearer(token(30))).text())
        assertEquals("still-good-key", c.held.keyId(), "recovery is a cheap assertion, not a re-attestation")
        assertEquals(1, c.key.asserted)
        assertEquals(0, c.key.attested)
    }

    @Test
    fun `a late rejection of a replaced token leaves the replacement alone`() = runTest {
        // B3: requests carrying T1 were in flight when a renewal stored T2; their refusals arrive afterwards.
        val c = Subject(held = Held(token = token(30), keyId = "k"))
        assertEquals(token(30), c.credential.rejected(bearer("T1-that-was-replaced")).text())
        assertEquals(token(30), c.held.token())
        assertEquals(0, c.client.challengeCalls, "the token held is fresh: nothing to renew")
    }

    @Test
    fun `a burst of rejections of one token renews once and every call retries with the new token`() = runTest {
        val c = Subject(held = Held(token = token(29), keyId = "k"))
        val retries = List(3) { async { c.credential.rejected(bearer(token(29))).text() } }.awaitAll()
        assertEquals(List(3) { token(30) }, retries, "only the first clears it, but all retry with what it obtained")
        assertEquals(1, c.client.renewCalls)
    }

    @Test
    fun `a rejection whose recovery obtains nothing offers no retry - and the token stays dropped`() = runTest {
        val c = Subject(client = FakeClient(challenge = null), held = Held(token = token(29), keyId = "k"))
        assertNull(c.credential.rejected(bearer(token(29))).text(), "nothing to send the call again with")
        assertNull(c.held.token(), "the rejected token is dropped, so the next wake renews")
    }

    @Test
    fun `a rejection whose compare cannot be made recovers nothing it should not`() = runTest {
        val held = Held(token = token(30), keyId = "k")
        val c = Subject(held = held, store = uncomparable(held.store))
        assertEquals(
            token(30),
            c.credential.rejected(bearer(token(30))).text(),
            "an unreadable store is never read as a cleared one",
        )
        assertEquals(0, c.client.challengeCalls, "the token still held is fresh: nothing is renewed over it")
    }

    @Test
    fun `a recovery over a store that cannot be read offers nothing and attests nothing`() = runTest {
        val held = Held(token = token(29), keyId = "k")
        val c = Subject(held = held, store = unreadable(held.store))
        assertNull(c.credential.rejected(bearer(token(29))).text(), "a rejected call stands as the backend answered it")
        assertNull(c.credential.missing().text(), "a request waits for no token this process cannot read")
    }

    @Test
    fun `an unreadable device identity leaves the token in place and never attests`() = runTest {
        val c = Subject(held = Held(token = token(1), keyId = "k"), identity = unreadableIdentity())
        assertIs<RefreshRead.NotObtained>(c.credential.refresh())
        assertEquals(0, c.client.challengeCalls, "a locked Keychain is not a failed assertion")
        assertEquals(token(1), c.held.token())
    }

    @Test
    fun `a device with no identity yet obtains nothing and attests nothing`() = runTest {
        val c = Subject(identity = StoredDeviceIdentity(inMemorySecureStore()))
        assertIs<RefreshRead.NotObtained>(c.credential.refresh())
        assertEquals(0, c.client.challengeCalls)
    }

    @Test
    fun `a key id that cannot be read keeps the token and attests nothing`() = runTest {
        // Minting over a forged "no key" would burn a fresh attestation for a key that was fine.
        val held = Held(token = token(1), keyId = "k")
        val c = Subject(held = held, store = unreadableKeyId(held.store))
        assertIs<RefreshRead.NotObtained>(c.credential.refresh())
        assertEquals(0, c.key.attested)
        assertEquals(token(1), held.token())
    }

    @Test
    fun `a chain that is not whole DER is never sent as fewer certificates`() = runTest {
        val c = Subject(integrity = BrokenChainKey())
        assertIs<RefreshRead.NotObtained>(c.credential.refresh())
        assertEquals(0, c.client.mintCalls, "nothing reaches the mint")
    }

    @Test
    fun `a key id the store cannot keep refuses the minted token`() = runTest {
        val held = Held()
        val c = Subject(held = held, store = refusingKeyId(held.store))
        assertIs<RefreshRead.NotObtained>(c.credential.refresh(), "a token whose key could not be kept is not accepted")
        assertEquals(1, c.client.mintCalls)
        assertNull(held.token(), "the minted token was never stored")
    }

    @Test
    fun `a renewed token the store cannot keep leaves the held one judging the device`() = runTest {
        val held = Held(token = token(1), keyId = "k")
        val c = Subject(held = held, store = refusingToken(held.store))
        assertIs<RefreshRead.NotObtained>(c.credential.refresh())
        assertEquals(1, c.client.renewCalls)
        assertTrue(c.status.attested.value, "the token held is still usable for a day")
    }

    @Test
    fun `a backend answer that names no token is no answer and keeps the token held`() = runTest {
        val c = Subject(held = Held(token = token(1), keyId = "k"))
        c.client.renewAnswer = HttpResult.Response(201, mapOf("Content-Type" to "application/json"), """{"no":"token"}""")
        assertIs<RefreshRead.NotObtained>(c.credential.refresh())
        assertEquals(token(1), c.held.token())
    }

    @Test
    fun `a refused build is reported from the attest routes too`() = runTest {
        val gate = AppVersionGate()
        val refusing = FakeClient().apply {
            challengeAnswer = HttpResult.Response(
                426,
                mapOf("Content-Type" to "application/json"),
                """{"error":"app too old","minAppVersion":"0.7"}""",
            )
        }
        Subject(client = refusing, versions = gate).credential.refresh()
        assertEquals(VersionRefusal("0.7"), gate.refusal.value, "an obsolete build learns it before it holds a token")
    }
}
