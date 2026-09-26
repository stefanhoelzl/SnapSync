@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.snapsync.attest

import app.snapsync.fake.InMemoryAttestStore
import app.snapsync.model.ApnsPushToken
import app.snapsync.model.CreateEventRequest
import app.snapsync.model.DeviceFile
import app.snapsync.model.DeviceManifest
import app.snapsync.model.EventCreated
import app.snapsync.model.EventMeta
import app.snapsync.model.EventRenamed
import app.snapsync.model.MintRequest
import app.snapsync.model.Proof
import app.snapsync.model.RenewRequest
import app.snapsync.model.Reply
import app.snapsync.model.TokenOutcome
import app.snapsync.model.UnionAsset
import app.snapsync.ports.AttestStore
import app.snapsync.ports.Backend
import app.snapsync.ports.DeviceIntegrity
import app.snapsync.services.trust.DeviceAttestation
import app.snapsync.services.trust.tokenExpirySeconds

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest

private const val DEVICE = "11111111-0000-4000-8000-000000000002"
private const val DAY_SECONDS = 24 * 60 * 60L
private const val NOW_SECONDS = 1_800_000_000L

/** A token in the backend's format: `<deviceId>.<expiry>.<signature>` — signed, so the device reads its own expiry. */
private fun token(expiresInDays: Long) = "$DEVICE.${NOW_SECONDS + expiresInDays * DAY_SECONDS}.sig"

private class FakeKey(
    val supported: Boolean = true,
    var attestThrows: Boolean = false,
    var assertThrows: Boolean = false,
) : DeviceIntegrity {
    var generated = 0
    var attested = 0
    var asserted = 0

    override fun isAvailable(): Boolean = supported
    override suspend fun prove(challenge: String, handle: String?): Proof {
        if (handle != null) {
            asserted++
            if (assertThrows) throw IllegalStateException("the Secure Enclave key is gone")
            return Proof(handle, byteArrayOf(4, 5, 6))
        }
        val key = "key-${++generated}"
        attested++
        if (attestThrows) throw IllegalStateException("Apple said no")
        return Proof(key, byteArrayOf(1, 2, 3))
    }
}

/** The backend's three `/attest/…` routes, scripted in the attestation's own vocabulary; every other route is unused. */
private class FakeClient(
    var challenge: String? = "chal",
    var mint: String? = token(30),
    var renew: String? = token(30),
) : Backend {
    var mintCalls = 0
    var renewCalls = 0
    var challengeCalls = 0

    /** Answers served before [mint]/[renew] apply, one per call — for the cases a bare token or refusal cannot say. */
    val mintAnswers = ArrayDeque<TokenOutcome>()
    val renewAnswers = ArrayDeque<TokenOutcome>()

    /** When set, `challenge()` suspends on it, so a test can observe a refresh while it is in flight. */
    var challengeGate: CompletableDeferred<Unit>? = null

    override suspend fun challenge(): Reply<String> {
        challengeGate?.await()
        challengeCalls++
        return challenge?.let { Reply.Ok(it) } ?: Reply.Unreachable(IllegalStateException("no challenge"))
    }

    override suspend fun mintToken(req: MintRequest): Reply<String> {
        mintCalls++
        return reply(mintAnswers.removeFirstOrNull() ?: mint?.let(TokenOutcome::Minted) ?: TokenOutcome.Refused)
    }

    override suspend fun renewToken(req: RenewRequest): Reply<String> {
        renewCalls++
        return reply(renewAnswers.removeFirstOrNull() ?: renew?.let(TokenOutcome::Minted) ?: TokenOutcome.Refused)
    }

    /** What the backend answers for [outcome] — the statuses and bodies the attestation service classifies. */
    private fun reply(outcome: TokenOutcome): Reply<String> = when (outcome) {
        is TokenOutcome.Minted -> Reply.Ok(outcome.token)
        TokenOutcome.ChallengeStale -> Reply.Refused(409, "stale challenge")
        TokenOutcome.NotAttested -> Reply.Refused(401, "not attested")
        TokenOutcome.Refused -> Reply.Refused(401, "attestation rejected")
        TokenOutcome.Unreachable -> Reply.Unreachable(IllegalStateException("offline"))
    }

    override suspend fun createEvent(token: String?, req: CreateEventRequest): Reply<EventCreated> = unused()
    override suspend fun getEvent(eventId: String): Reply<EventMeta> = unused()
    override suspend fun renameEvent(token: String?, eventId: String, name: String): Reply<EventRenamed> = unused()
    override suspend fun joinEvent(token: String?, eventId: String, deviceId: String): Reply<Unit> = unused()
    override suspend fun publishManifest(token: String?, eventId: String, deviceId: String, manifest: DeviceManifest): Reply<Unit> =
        unused()
    override suspend fun leaveEvent(token: String?, eventId: String, deviceId: String): Reply<Unit> = unused()
    override suspend fun eventFiles(eventId: String): Reply<List<UnionAsset>> = unused()
    override suspend fun deviceFiles(token: String?, deviceId: String): Reply<List<DeviceFile>> = unused()
    override suspend fun putDeviceConfig(token: String?, deviceId: String, push: ApnsPushToken): Reply<Unit> = unused()

    private fun unused(): Nothing = error("attestation reaches only the /attest/… routes")
}

private fun attestation(
    key: FakeKey = FakeKey(),
    client: FakeClient = FakeClient(),
    store: AttestStore = InMemoryAttestStore(),
) = Triple(
    DeviceAttestation(key, client, store, { DEVICE }, clock = { kotlin.time.Instant.fromEpochSeconds(NOW_SECONDS) }),
    client,
    store,
)

class DeviceAttestationTest {

    // ---- attested: the SyncHealth.Unattested rule (the trust feature's, not the shell's) ----

    @Test
    fun `attested is true when the refresh obtains a token`() = runTest {
        val (attestation, _, _) = attestation()
        attestation.refresh()
        assertTrue(attestation.attested.value)
    }

    @Test
    fun `attested is true for a usable token even if a concurrent path reported no refresh`() = runTest {
        // A fresh token short-circuits ensureFresh to true anyway; the second clause additionally
        // covers any path where the refresh reports false while the stored token is still usable.
        val (attestation, client, _) = attestation(store = InMemoryAttestStore(token(20), "k"))
        client.challenge = null // even with no network, a fresh token is a non-event
        attestation.refresh()
        assertTrue(attestation.attested.value)
    }

    @Test
    fun `attested is false when there is no token at all and none can be obtained`() = runTest {
        val (attestation, client, _) = attestation()
        client.challenge = null // offline: no challenge, nothing stored
        attestation.refresh()
        assertFalse(attestation.attested.value)
    }

    // The SNAPSYNC-20 correction. `isStale` fires a full week before expiry because renewing eagerly is
    // the only thing that keeps the token alive across iOS starving the app's background wakes. Reusing
    // it as the SURFACING rule told a member on 2026-08-18 that sharing was paused while their token had
    // six days left and every upload was authorized. The test that was here asserted the opposite in its
    // NAME -- "false only when the device lacks a usable token AND could not get one" -- while passing an
    // EMPTY store, so the word "only" was carried by the name and by nothing else.
    @Test
    fun `attested stays true for a token inside the renewal margin whose renewal fails`() = runTest {
        val (attestation, client, _) = attestation(store = InMemoryAttestStore(token(3), "k"))
        client.challenge = null // offline, exactly as on 2026-08-17
        attestation.refresh()

        assertTrue(attestation.isStale(token(3)), "the token IS due for renewal - that is why we tried")
        assertFalse(attestation.isUnusable(token(3)), "but it still authorizes every gated request")
        assertTrue(attestation.attested.value, "so nothing is surfaced: no upload is stalled")
    }

    @Test
    fun `attested is false for an expired token that could not be replaced`() = runTest {
        val (attestation, client, _) = attestation(store = InMemoryAttestStore(token(-1), "k"))
        client.challenge = null
        attestation.refresh()
        assertFalse(attestation.attested.value)
    }

    @Test
    fun `an unreadable token counts as unusable - never usable-until-proven-otherwise`() = runTest {
        // Its expiry cannot be read, so it cannot be shown to be valid and the backend will reject it.
        // Calling it usable would put the member back behind a screen reading "Syncing" while every
        // upload 401s -- the lie the Unattested rung exists to prevent.
        val (attestation, client, _) = attestation(store = InMemoryAttestStore("not-a-token", "k"))
        client.challenge = null
        attestation.refresh()
        assertFalse(attestation.attested.value)
    }

    @Test
    fun `narrowing what is SURFACED did not narrow when the app renews`() = runTest {
        val key = FakeKey()
        val (attestation, client, _) = attestation(key, store = InMemoryAttestStore(token(3), "k"))

        attestation.refresh()

        assertEquals(1, client.renewCalls, "a margin token must still renew at every wake")
        assertEquals(1, key.asserted)
    }

    @Test
    fun `a verdict is cleared when the next refresh BEGINS - not when it ends`() = runTest {
        // The other half of SNAPSYNC-20. A background wake with no network wrote `false` on 2026-08-17
        // 13:37; the process stayed alive but suspended, and the member saw that verdict rendered as the
        // first frame of a foreground entry 25 h 47 min later, under conditions that no longer held.
        val (attestation, client, _) = attestation(store = InMemoryAttestStore(token(-1), "k"))
        client.challenge = null
        attestation.refresh()
        assertFalse(attestation.attested.value, "the offline wake concluded the device is stuck")

        // The next wake, held inside the challenge fetch so the refresh is provably still in flight.
        val gate = CompletableDeferred<Unit>()
        client.challengeGate = gate
        val running = launch { attestation.refresh() }
        runCurrent()

        assertTrue(
            attestation.attested.value,
            "the earlier wake's verdict must be gone the moment this refresh starts, not when it finishes",
        )

        gate.complete(Unit) // still offline, so this attempt fails too
        running.join()
        assertFalse(attestation.attested.value, "and this attempt's own answer replaces it")
    }

    @Test
    fun `a device that has never attested attests and persists both the key and the token`() = runTest {
        val key = FakeKey()
        val (attest, client, store) = attestation(key)

        assertTrue(attest.ensureFresh())

        assertEquals(1, key.attested)
        assertEquals(0, key.asserted) // nothing to assert WITH yet
        assertEquals(1, client.mintCalls)
        assertEquals("key-1", store.keyId())
        assertEquals(token(30), store.token())
    }

    @Test
    fun `an attested device RENEWS with an assertion - never a second attestation`() = runTest {
        // This is the load-bearing one. Apple attests a key ONCE; re-attesting (or minting a fresh key each
        // time) is the throttled path. If renewal ever regressed to a re-attestation, it would have to
        // become rare, and rare renewal means a narrow window near expiry — the exact failure the design
        // exists to avoid.
        val key = FakeKey()
        val store = InMemoryAttestStore(token = token(1), keyId = "existing-key")
        val (attest, client, _) = attestation(key, store = store)

        assertTrue(attest.ensureFresh())

        assertEquals(1, key.asserted)
        assertEquals(0, key.attested) // NOT re-attested
        assertEquals(0, key.generated) // and no new key minted
        assertEquals(1, client.renewCalls)
        assertEquals(token(30), store.token())
    }

    @Test
    fun `a fresh token is left alone - no challenge no network no Secure Enclave`() = runTest {
        val key = FakeKey()
        val store = InMemoryAttestStore(token = token(29), keyId = "existing-key")
        val (attest, client, _) = attestation(key, store = store)

        assertTrue(attest.ensureFresh())

        assertEquals(0, key.asserted)
        assertEquals(0, key.attested)
        assertEquals(0, client.renewCalls)
        assertEquals(0, client.mintCalls)
        assertEquals(token(29), store.token()) // untouched
    }

    @Test
    fun `renewal starts once less than 7 days remain - not at expiry`() = runTest {
        // The margin is the whole point: waiting for expiry would mean the FIRST failed upload is what
        // discovers the dead token.
        val (fresh, _, _) = attestation(store = InMemoryAttestStore(token(8), "k"))
        assertFalse(fresh.isStale(token(8)))

        val (stale, _, _) = attestation(store = InMemoryAttestStore(token(6), "k"))
        assertTrue(stale.isStale(token(6)))
    }

    @Test
    fun `an absent or expired token is stale`() = runTest {
        val (attest, _, _) = attestation()
        assertTrue(attest.isStale(null))
        assertTrue(attest.isStale(token(-1)))
        assertTrue(attest.isStale("not-a-token"))
    }

    @Test
    fun `a refused renewal falls back to a full attestation rather than stalling forever`() = runTest {
        // The backend's record of this device can legitimately vanish — the leave cascade GCs it. Were the
        // client to keep asserting against a key the server has never heard of, the device would 401
        // forever with no way back.
        val key = FakeKey()
        val client = FakeClient(renew = null) // the backend refuses the assertion
        val store = InMemoryAttestStore(token = token(1), keyId = "forgotten-key")
        val (attest, _, _) = attestation(key, client, store)

        assertTrue(attest.ensureFresh())

        assertEquals(1, key.asserted) // tried to renew…
        assertEquals(1, key.attested) // …then attested afresh
        assertEquals("key-1", store.keyId()) // and re-pointed at the NEW key
        assertEquals(token(30), store.token())
    }

    @Test
    fun `a failed attestation stores nothing and never throws`() = runTest {
        // ensureFresh runs on background wakes. A throw here would take down work that has nothing to do
        // with attestation.
        val key = FakeKey(attestThrows = true)
        val (attest, _, store) = attestation(key)

        assertFalse(attest.ensureFresh())

        assertNull(store.token())
        assertNull(store.keyId())
    }

    @Test
    fun `a renewal whose challenge went stale retries once with a fresh one and keeps the key`() = runTest {
        // B2's root: the app is suspended between fetching a challenge and using it. The answer is one fresh
        // challenge — not a new key on Apple's throttled path, and never a dropped token.
        val key = FakeKey()
        val client = FakeClient().apply { renewAnswers += TokenOutcome.ChallengeStale }
        val store = InMemoryAttestStore(token = token(1), keyId = "k")
        val (attest, _, _) = attestation(key, client, store)

        assertTrue(attest.ensureFresh())

        assertEquals(2, client.challengeCalls)
        assertEquals(2, client.renewCalls)
        assertEquals(0, key.attested, "a stale challenge is not a reason to attest afresh")
        assertEquals(token(30), store.token())
    }

    @Test
    fun `a renewal that stays stale or gets no answer keeps the token and attests nothing`() = runTest {
        for (answers in listOf(
            listOf(TokenOutcome.ChallengeStale, TokenOutcome.ChallengeStale),
            listOf(TokenOutcome.Unreachable),
        )) {
            val key = FakeKey()
            val client = FakeClient().apply { renewAnswers += answers }
            val store = InMemoryAttestStore(token = token(1), keyId = "k")
            val (attest, _, _) = attestation(key, client, store)

            assertFalse(attest.ensureFresh(), "$answers")

            assertEquals(token(1), store.token(), "$answers must not touch the token the device holds")
            assertEquals(0, key.attested, "$answers says nothing about this device — no throttled attestation")
            assertEquals(0, client.mintCalls)
        }
    }

    @Test
    fun `an attestation whose challenge went stale retries once`() = runTest {
        val client = FakeClient().apply { mintAnswers += TokenOutcome.ChallengeStale }
        val (attest, _, store) = attestation(client = client)

        assertTrue(attest.ensureFresh())

        assertEquals(2, client.mintCalls)
        assertEquals(token(30), store.token())
    }

    @Test
    fun `a backend that refuses the attestation leaves no keyId behind`() = runTest {
        // A keyId stored for an attestation the backend never recorded would send every future renewal down
        // the assertion path, against a key the server has never heard of — a permanent 401 loop.
        val (attest, _, store) = attestation(client = FakeClient(mint = null))

        assertFalse(attest.ensureFresh())

        assertNull(store.keyId())
        assertNull(store.token())
    }

    @Test
    fun `no challenge means the existing token is left in place - not discarded`() = runTest {
        val store = InMemoryAttestStore(token = token(1), keyId = "k")
        val (attest, _, _) = attestation(client = FakeClient(challenge = null), store = store)

        assertFalse(attest.ensureFresh())

        assertEquals(token(1), store.token()) // still usable for a day — better than nothing
    }

    @Test
    fun `a process without App Attest never attests - this is the extension`() = runTest {
        // DCAppAttestService.isSupported is FALSE in the upload extension (verified on device). The
        // extension must read the app's token and send it, never try to mint its own.
        val key = FakeKey(supported = false)
        val store = InMemoryAttestStore(token = token(1))
        val (attest, client, _) = attestation(key, store = store)

        attest.ensureFresh()

        assertEquals(0, key.attested)
        assertEquals(0, key.asserted)
        assertEquals(0, client.mintCalls)
        assertEquals(0, client.renewCalls)
        assertEquals(token(1), store.token()) // it just reads what the app left
    }

    @Test
    fun `the token is readable even when expired - the extension sends it and lets the 401 retry`() {
        val store = InMemoryAttestStore(token = token(-5))
        val (attest, _, _) = attestation(store = store)

        // Deliberately NOT null: refusing to hand out an expired token would mean building no request at
        // all, which strands the resource. A 401 is a retryable failure; a missing request is not.
        assertEquals(token(-5), attest.token())
    }

    @Test
    fun `tokenExpirySeconds reads the expiry the backend signed and rejects nonsense`() {
        assertEquals(NOW_SECONDS + 30 * DAY_SECONDS, tokenExpirySeconds(token(30)))
        assertNull(tokenExpirySeconds("garbage"))
        assertNull(tokenExpirySeconds("a.b.c"))
    }

    @Test
    fun `a REJECTED token is dropped even though it is nowhere near expiry`() = runTest {
        // The bug this exists to prevent: `isStale` only knows about EXPIRY. A token can be rejected while
        // still 30 days from expiring — after the signing key is rotated, or after the leave cascade
        // collects this device's attestation record. Treating "rejected" as "fine" made the app re-send the
        // same dead credential forever, 401ing behind a screen that cheerfully said "Syncing".
        val store = InMemoryAttestStore(token = token(30), keyId = "k")
        val (attest, _, _) = attestation(store = store)

        assertFalse(attest.isStale(store.token())) // looks perfectly healthy…
        assertTrue(attest.onRejected(token(30))) // …but the backend said 401 to it

        assertNull(store.token())
        assertTrue(attest.isStale(store.token())) // so the next wake WILL renew
    }

    @Test
    fun `a rejected token keeps its keyId so recovery is a cheap assertion`() = runTest {
        // Recovery must not pay for a full re-attestation: Apple attests a key ONCE, and re-attesting is
        // the throttled path. The Secure-Enclave key is still perfectly good — only the server-issued token
        // died — so the keyId is kept and the next refresh renews with an assertion.
        val key = FakeKey()
        val store = InMemoryAttestStore(token = token(30), keyId = "still-good-key")
        val (attest, client, _) = attestation(key, store = store)

        attest.onRejected(token(30))
        assertTrue(attest.ensureFresh())

        assertEquals("still-good-key", store.keyId())
        assertEquals(1, key.asserted) // renewed…
        assertEquals(0, key.attested) // …NOT re-attested
        assertEquals(1, client.renewCalls)
        assertEquals(token(30), store.token())
    }

    @Test
    fun `a late rejection of a replaced token leaves the replacement alone and asks for nothing`() = runTest {
        // B3: requests carrying T1 were in flight when a renewal stored T2; their refusals arrive afterwards.
        // Clearing unconditionally erased T2 and sent the device round the loop once per late refusal.
        val store = InMemoryAttestStore(token = token(30), keyId = "k")
        val (attest, _, _) = attestation(store = store)
        val t1 = "T1-that-was-replaced"

        assertFalse(attest.onRejected(t1), "a token we no longer hold is not ours to clear")
        assertEquals(token(30), store.token())
    }

    @Test
    fun `a burst of rejections of one token clears it once`() = runTest {
        // Every in-flight request carrying the dead token is refused. The first clears it; the rest find nothing
        // of theirs to clear, so the caller triggers one refresh, not one per request.
        val store = InMemoryAttestStore(token = token(30), keyId = "k")
        val (attest, _, _) = attestation(store = store)

        assertEquals(listOf(true, false, false), List(3) { attest.onRejected(token(30)) })
    }

    @Test
    fun `concurrent refreshes collapse into ONE renewal`() = runTest {
        // Observed on device: a rejected token 401s every in-flight request, and each one asked for a
        // refresh — three concurrent /attest/renew calls for a single rejection. The second and third are
        // pure waste aimed at Apple's throttled path; the first already fixed it.
        val key = FakeKey()
        val store = InMemoryAttestStore(token = token(1), keyId = "k")
        val (attest, client, _) = attestation(key, store = store)

        val results = List(5) { async { attest.ensureFresh() } }.awaitAll()

        assertTrue(results.all { it })
        assertEquals(1, client.renewCalls) // …not 5
        assertEquals(1, key.asserted)
    }

    @Test
    fun `obtaining a token announces it - so a refused registration can be re-sent`() = runTest {
        // The APNs registration PUT is gated, and is sent only when the token differs from the last
        // registration the backend accepted. If it was refused because this device had not attested yet, a new
        // credential is what re-sends it at once — so a mint and a renew must both announce themselves, or the
        // device waits unregistered for its next app entry.
        val minted = attestation()
        val mints = mutableListOf<Unit>()
        backgroundScope.launch { minted.first.tokenChanged.toList(mints) }
        runCurrent()

        assertTrue(minted.first.ensureFresh()) // a fresh install ATTESTS
        runCurrent()
        assertEquals(1, mints.size)

        val renewed = attestation(store = InMemoryAttestStore(token = token(1), keyId = "k"))
        val renews = mutableListOf<Unit>()
        backgroundScope.launch { renewed.first.tokenChanged.toList(renews) }
        runCurrent()

        assertTrue(renewed.first.ensureFresh()) // an attested device RENEWS
        runCurrent()
        assertEquals(1, renews.size)
    }

    // ---- the in-memory token copy: what each process sees of the other's writes ----

    @Test
    fun `a token the extension dropped is seen at the next wake - and renewed`() = runTest {
        // The extension compare-and-clears a rejected token in the SHARED item; this process's copy cannot see
        // that. Every refresh re-reads the store of record first, so the wake that follows renews it.
        val store = InMemoryAttestStore(token = token(30), keyId = "k")
        val (attest, client, _) = attestation(client = FakeClient(renew = token(29)), store = store)
        assertEquals(token(30), attest.token())

        store.clearToken() // the extension, in its own process

        attest.refresh()

        assertEquals(1, client.renewCalls, "the refresh must see the drop, not the copy")
        assertEquals(token(29), attest.token())
        assertTrue(attest.attested.value)
    }

    @Test
    fun `a rejection of a token the other process already dropped re-reads - and triggers nothing`() = runTest {
        val store = InMemoryAttestStore(token = token(30), keyId = "k")
        val (attest, _, _) = attestation(store = store)
        assertEquals(token(30), attest.token())
        store.clearToken() // the extension dropped it; a request carrying our copy is then refused

        assertFalse(attest.onRejected(token(30)), "the compare runs against the store, which no longer holds it")

        assertNull(attest.token(), "and the next request reads what the store holds, not the copy")
    }

    @Test
    fun `this process's own renewal is what the next request carries`() = runTest {
        val store = InMemoryAttestStore(token = token(1), keyId = "k")
        val (attest, _, _) = attestation(store = store)
        assertEquals(token(1), attest.token())

        assertTrue(attest.ensureFresh())

        assertEquals(token(30), attest.token())
    }

    // ---- the app's credential: what the authenticated backend retries with ----

    @Test
    fun `a rejection drops the token and answers the renewed one to retry with`() = runTest {
        val store = InMemoryAttestStore(token = token(29), keyId = "k")
        val (attest, client, _) = attestation(store = store)

        assertEquals(token(30), attest.rejected(token(29)), "the call is retried with what the renewal obtained")
        assertEquals(1, client.renewCalls)
        assertEquals(token(30), store.token())
    }

    @Test
    fun `a burst of rejections of one token renews once and every call retries with the new token`() = runTest {
        val store = InMemoryAttestStore(token = token(29), keyId = "k")
        val (attest, client, _) = attestation(store = store)

        val retries = List(3) { async { attest.rejected(token(29)) } }.awaitAll()

        assertEquals(List(3) { token(30) }, retries, "only the first clears it, but all retry — not only the one that cleared")
        assertEquals(1, client.renewCalls, "the refresh is a no-op on the fresh token the first one obtained")
    }

    @Test
    fun `a rejection whose recovery obtains nothing offers no retry`() = runTest {
        val store = InMemoryAttestStore(token = token(29), keyId = "k")
        val client = FakeClient(challenge = null)
        val (attest, _, _) = attestation(client = client, store = store)

        assertNull(attest.rejected(token(29)), "no token to send is no reason to send the call again")
        assertNull(store.token(), "the rejected token is still dropped, so the next wake renews")
    }

    @Test
    fun `a refused build is reported from the attest routes too`() = runTest {
        val gate = app.snapsync.services.version.AppVersionGate()
        val refusing = object : Backend by FakeClient() {
            override suspend fun challenge(): Reply<String> = Reply.Refused(426, """{"minAppVersion":"0.7"}""")
        }
        val attest = DeviceAttestation(
            FakeKey(), refusing, InMemoryAttestStore(), { DEVICE },
            clock = { kotlin.time.Instant.fromEpochSeconds(NOW_SECONDS) }, versionGate = gate,
        )

        attest.refresh()

        assertEquals(app.snapsync.model.VersionRefusal("0.7"), gate.refusal.value, "an obsolete build learns it before it holds a token")
    }

    // ---- the failure paths a wake must survive ----

    @Test
    fun `an assertion the Secure Enclave cannot produce falls back to a full attestation`() = runTest {
        val key = FakeKey(assertThrows = true)
        val store = InMemoryAttestStore(token = token(1), keyId = "k")
        val (attest, client, _) = attestation(key, store = store)

        assertTrue(attest.ensureFresh())

        assertEquals(0, client.renewCalls, "no request was sent for an assertion that was never produced")
        assertEquals(1, key.attested)
        assertEquals("key-1", store.keyId(), "the fresh key replaces the dead one")
    }

    @Test
    fun `an unreadable device identity leaves the token in place and never attests`() = runTest {
        val key = FakeKey()
        val client = FakeClient()
        val store = InMemoryAttestStore(token = token(1), keyId = "k")
        val attest = DeviceAttestation(
            key, client, store, { error("keychain locked") },
            clock = { kotlin.time.Instant.fromEpochSeconds(NOW_SECONDS) },
        )

        assertFalse(attest.ensureFresh())

        assertEquals(0, client.challengeCalls, "a locked Keychain is not a failed assertion")
        assertEquals(token(1), store.token())
    }

    @Test
    fun `a backend answer that names no token is no answer and keeps the token held`() = runTest {
        val client = object : Backend by FakeClient() {
            override suspend fun challenge(): Reply<String> = Reply.Ok("chal")
            override suspend fun renewToken(req: RenewRequest): Reply<String> = Reply.Malformed("no token")
        }
        val store = InMemoryAttestStore(token = token(1), keyId = "k")
        val attest = DeviceAttestation(
            FakeKey(), client, store, { DEVICE },
            clock = { kotlin.time.Instant.fromEpochSeconds(NOW_SECONDS) },
        )

        assertFalse(attest.ensureFresh())
        assertEquals(token(1), store.token())
    }

    @Test
    fun `a retry reads the store of record not this process's copy`() = runTest {
        val store = InMemoryAttestStore(token = token(30))
        val (attest, _, _) = attestation(store = store)
        assertEquals(token(30), attest.token()) // cached

        store.setToken(token(31)) // the other process renewed into the shared item

        assertEquals(token(31), attest.freshToken())
    }

    @Test
    fun `a rejection whose compare cannot be made is logged and recovers nothing it should not`() = runTest {
        val store = object : AttestStore by InMemoryAttestStore(token = token(30), keyId = "k") {
            override fun clearTokenIf(expected: String): Boolean = throw IllegalStateException("keychain locked")
        }
        val (attest, _, _) = attestation(store = store)

        assertFalse(attest.onRejected(token(30)), "an unreadable store is never read as a cleared token")
    }
}
