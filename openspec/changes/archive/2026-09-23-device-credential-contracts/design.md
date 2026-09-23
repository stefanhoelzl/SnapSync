## Context

Phase 8c of the testing-concept sequence. The handoff grouped five port families as "device-only credential
and state" ports. Checked against the tree (2026-09-23, `7cc930a7`), the list reads differently:

| Port | Real implementation | In-memory double | Can CI reach it? |
|---|---|---|---|
| `AttestKey` | `IosAttestKey` (`:adapter:ios:ext-safe`, `DCAppAttestService` directly) | `inMemoryAttestKey` — **the world composes it** | refusal: yes (`IOS_SIM_KEXE`, `isSupported` false); ceremony: device only |
| `AttestStore` | `KeychainAttestStore` = two `IosKeychain` items | `inMemoryAttestStore` — the world composes it | `INACCESSIBLE`: yes (`IOS_SIM_KEXE`, `-25291`); readable: device only (`IOS_SIM_APP` answers `-34018`) |
| `AttestClient` mint/renew | `HttpAttestClient` + the edge | `inMemoryAttestClient` — the world composes it | refusals: yes (`JVM`, live edge); success: no host |
| `PushHttpClient` | `KtorPushHttpClient` | none (the world uses the real client over the mini-edge) | `JVM` — nothing about it involves APNs |
| `PushTokenSource` | a concrete settable class, its own double | — | not a port with an adapter |
| `PushReceiver` | domain features (`UploadPushReceiver`, `DownloadPushReceiver`) | — | OS entry already contracted in `PlatformEntriesContract` |
| `ProtectedStorage` | `IosProtectedStorage` (`:adapter:ios:app-only`, `UIApplication`) | `inMemoryProtectedStorage` — the world composes it | unlocked: yes (`IOS_SIM_APP`); locked: no host |
| `ProcessMetricSource` | `MetricKitProcessMetricSource`, built directly by `SnapSyncRoot` | **none** | no host can make the OS deliver |

The mechanism is phase 3's (`port-contracts`). `SecureStore` is the worked example of a device recording
replayed in CI: the `KeychainApi` seam, `DeviceKeychainBinding` and `deviceContracts()` in
`:adapter:ios:ext-safe`'s `rig` source set, and `IosKeychainReplayContractTest` in `iosTest`. The live
backend binding is phase 7's `LiveEdgeContractsTest` over `serve.ts --ephemeral`. The simulator-app registry
is `SimulatorAppContracts.kt` via `simulatorAppContract(...)`.

The repository is **public**, which constrains what a recording may carry (D3).

## Goals / Non-Goals

**Goals:**
- Back every double in the table above with a contract its real implementation passes on some host, or
  record why none can exist, with the evidence.
- Fix each double a clause shows to be dishonest, rather than steering its binding to a state that hides
  the dishonesty.
- Close phase 7's D7 the way it named: narrow the push seam to its meaning, then contract it.
- Keep the device session to one trip: `AttestKey` and `AttestStore` together.

**Non-Goals:**
- Successful `mintToken`/`renewToken` against a real edge (D4).
- A contract for `ProcessMetricSource` (D7), or anything about `DiagnosticsReporter` (phase 8d).
- `isSupported() == false` inside the upload extension. The extension is an unbound host. The belief stays
  in `IosAttestKey`'s documentation with its SE2 measurement.
- Contracting `PushTokenSource`/`PushReceiver` (see Context). Changing APNs acquisition or silent-push
  handling.
- Any production behaviour change.

## Decisions

### D1. `AttestKey`: two states, two hosts, one new seam

States: `UNSUPPORTED` and `SUPPORTED`.

- **`UNSUPPORTED`** runs live on `IOS_SIM_KEXE`, over the production `IosAttestKey()`. The clauses:
  it reports itself unsupported; `generateKey` refuses; `attest` and `assert` with an unknown key refuse.
  Every refusal is an exception, never a hang and never an invented answer. These are `IosAttestKeyTest`'s refusal assertions made into
  clauses. The file keeps only what no other implementation of the port should have to match: `IosAttestKey`'s
  own diagnostic, which names the step and carries the platform's domain and code. The rest would be a copy.
- **`SUPPORTED`** is recorded on `IOS_DEVICE_APP` and replayed on every build. The clauses: it reports
  itself supported; a generated key attests to a fixed challenge and then asserts over it, each answer
  non-empty; an unknown key is refused at attest and at assert.
- **The seam.** `internal interface AppAttestApi` in `:adapter:ios:ext-safe` `iosMain`, with one method per
  `DCAppAttestService` call (`isSupported`, `generateKey`, `attestKey(keyId, clientDataHash)`,
  `generateAssertion(keyId, clientDataHash)`), and a `SystemAppAttestApi` object as the only production
  implementation. `IosAttestKey` takes it as a defaulted constructor parameter. The SHA-256 of the
  challenge stays in `IosAttestKey`, **above** the seam. That placement is the reason to record at all:
  the recorded request carries the `clientDataHash`, so a replay fails as `Diverged` if the adapter ever
  hashes differently. The edge's verifier depends on exactly that hash (`SHA256(authData ‖
  SHA256(challenge))`).
- *Alternative rejected:* a documented exclusion for the whole port. The device **can** run it, and
  `port-contracts` sends a belief to documentation only when *no* host can exercise it.

### D2. `AttestStore`: add it to the same recording

`KeychainAttestStore` is two `IosKeychain` items, and `IosKeychain` already goes through the recorded
`KeychainApi` seam. The contract's states are `INACCESSIBLE`, `EMPTY` and `HOLDING` (a token and a
`keyId`). The clauses: an unreadable store **throws** on `token()` and `keyId()` and never answers null;
an empty store answers null for both; a write reads back; `clearToken` drops the token and keeps the
`keyId`. `INACCESSIBLE` runs live on `IOS_SIM_KEXE`. `EMPTY`/`HOLDING` are recorded on `IOS_DEVICE_APP`.
Item addresses derive from the clause id, as `SecureStoreContract`'s do. So the production default
addresses are not covered, and that is the same precedent `SecureStore` set.
*Alternative rejected:* relying on `SecureStore` plus `KeychainAttestStoreTest`. Neither one binds
`InMemoryAttestStore`, and the world composes that double.

### D3. What a recording of App Attest may contain

The `keyId`, the attestation bytes and the assertion bytes are masked. A key's attestation carries
Apple's certificate chain and a receipt for this device's key, and a public repository must not hold that.
The `keyId` is minted by the OS and sent back by the adapter, so it is masked in the answer **and** in the
requests that carry it. Replay then hands the placeholder back to `IosAttestKey`, which sends it on, and
the recorded request still matches. This is the `port-contracts` delta. Today `maskKeys` masks answers
only. The App Attest tape masks both sides with one placeholder, which is enough because no clause asserts
anything about two keys' identities.
The consequence, accepted: a replay proves the call sequence and the hash, and it proves that a non-empty
answer is handled. It does **not** prove that Apple's bytes verify at the edge. `api/test/attest.test.ts`
proves verification against a real Apple fixture.

### D4. `AttestClient`: contract the refusals, document the successes

Three clauses in state `SERVING`, run on `JVM` against the live edge:
`A_FORGED_ATTESTATION_IS_REFUSED` (a real challenge with garbage bytes gives null),
`A_CHALLENGE_THE_EDGE_NEVER_ISSUED_IS_REFUSED`, and `AN_UNATTESTED_DEVICE_CANNOT_RENEW`. The in-memory
double is bound in `:adapter:generic:fake` `commonTest`.
The success paths cannot be replayed from a device recording. The challenge is
`<expiry>.<HMAC>` with a 300 s TTL (`CHALLENGE_TTL_SECONDS`), and the certificate chain is verified at
request time. A recorded attestation is therefore dead five minutes after it was taken. Making the
ephemeral edge accept it would need a pinned clock and a pinned HMAC key in `serve.ts`, which is the dev
rig faking attestation, a line phase 7 drew deliberately. The success beliefs stay in `HttpAttestClient`'s
KDoc and `HttpAttestClientTest`, where phase 7 put them. The contract's header comment is updated to say
which clauses exist and why the rest do not.

### D5. Honest doubles

The new clauses fail against two doubles as they stand. The fix goes in the doubles:
- `InMemoryAttestKey(supported = false)` throws from `generateKey`/`attest`/`assert`, and a supported one
  throws for a `keyId` it never generated. Every production caller checks `isSupported()` first
  (`DeviceAttestation.refreshLocked`), so the world is unchanged.
- `InMemoryAttestClient` mints only for `attestation == "attestation:<keyId>:<challenge>"` (the shape
  `InMemoryAttestKey` produces) over the challenge it issued. The `mints` knob stays: it models a backend
  that refuses a **genuine** attestation. The world pairs the two doubles, so its attested path still
  mints.
- `renews` stays as it is. Its default of `false` is exactly the unattested-device clause. A double built
  with `renews = true` models a device the edge holds an enrolment for, which no host reaches, and the KDoc
  says so.
*Alternative rejected:* binding the double with `mints = false` so the refusal clauses pass. That uses the
binding to hide the lie the clause exists to catch.

### D6. `PushTokenPublisher`: narrow the seam, then contract it

`PushHttpClient.put(url, body)`/`post(url)` becomes
`interface PushTokenPublisher { suspend fun publish(token: ApnsPushToken): Result<Unit> }`, named for the
need. `HttpPushTokenPublisher(client, host, deviceId: () -> String)` in `:adapter:generic:app` `push/`
takes over the URL and `deviceConfigJson`. Its constructor matches the shape of `HttpLeaveNotifier`, and
it keeps the supplier-not-value rule from `device-identity`. `ApnsPushToken` moves from `feature/push` to
`model/`, because a port may name only `model/` (ports→model gate). Its name is kept: the body's
`kind: "apns"` is the wire truth. An Android binding would widen this type, and that future is named, not
built. `post` is deleted, since its last caller (`EventNotifier`) left with the versioned device API.

Contract states: `ENROLLED`, and `REJECTED_CREDENTIAL` (a bearer no edge issued, `Edge.kt`'s existing
identity). The clauses: a publish succeeds; a second publish with a rotated token succeeds
(last-write-wins); under a rejected credential the publish is a **failed result, never a throw**. The live
binding enters `ENROLLED` for free. The ephemeral edge's fallback bearer enrols the device named on exactly
this route (`fallback.ts` `enrolmentTarget`). No clause reads the stored token back, because the edge
exposes no read of it and `EdgeSetup` goes through the public surface only. The mini-edge is bound as the
`Fake` in `:test:world` `commonTest`. It declares unreachable any state it does not model, so the live
edge still carries it. `KtorPushHttpClientTest`'s status mapping moves into an `HttpPushTokenPublisherTest`
for the `502`/malformed cases the live edge cannot be made to produce.
*Alternative rejected:* contracting the generic transport. A clause would have to build `/devices/<id>`
itself and restate the feature (phase 7's D7).

### D7. `ProtectedStorage` on `IOS_SIM_APP`; `ProcessMetricSource` nowhere

- `ProtectedStorageContract` has one state, `UNLOCKED`, and one clause: a running, unlocked app reads
  protected storage as readable. It is bound live on `IOS_SIM_APP` through `SimulatorAppContracts.kt`, and
  the `ios-contracts` job runs it on every push. It is also bound on the double. `IOS_SIM_KEXE` is
  unreachable there: a test executable has no `UIApplication`. What the clause adds is modest, and it is
  real. It exercises the main-lane hop that `UIApplication` requires and would catch a hang or a crash.
  The locked state has no clause. `port-contracts` ("Hosts are a closed set…") already records that no
  host can enter it, and `IosProtectedStorage`'s KDoc now says the same and cites it.
- `ProcessMetricSource` gets no contract. There is no double to back, and delivery is OS-timed and
  one-shot, so no binding can enter a state. A replay would only replay a payload we chose. Its
  `MetricKitProcessMetricSource` KDoc gains one paragraph that states this and points at the evidence
  already there.

### D8. Where the code lands

| Piece | Module / source set |
|---|---|
| `AttestKeyContract`, `AttestStoreContract`, `PushTokenPublisherContract`, `ProtectedStorageContract`, new `AttestClientContract` clauses | `:test:contracts` `commonMain` |
| `AppAttestApi` + `SystemAppAttestApi` | `:adapter:ios:ext-safe` `iosMain` |
| App Attest tape (recording/replaying `AppAttestApi`), device bindings, `deviceContracts()` entries | `:adapter:ios:ext-safe` `rig` |
| `IOS_SIM_KEXE` live bindings + the two replay tests | `:adapter:ios:ext-safe` `iosTest` |
| `ProtectedStorage` live binding on `IOS_SIM_APP` | `:adapter:ios:app-only` `rig` (`SimulatorAppContracts.kt`) |
| Double bindings (`AttestKey`, `AttestStore`, `AttestClient`, `ProtectedStorage`) | `:adapter:generic:fake` `commonTest` |
| Live-edge `AttestClient`/`PushTokenPublisher` bindings | `:adapter:generic:app` `jvmTest` (`LiveEdgeContractsTest`) |
| Mini-edge `PushTokenPublisher` binding | `:test:world` `commonTest` |

## Risks / Trade-offs

- [Each recording asks Apple for a real attestation, which is throttled] → Record `AttestKey` once for this
  change. A re-record is needed only on `Diverged`, which is an adapter change asking the OS something
  new, never a comment edit.
- [Masked bytes make the replay weaker than the live run] → Accepted and stated in D3. The live device
  outcome is written into the recording's header (`live <CLAUSE> …`), so the committed file still says the
  device passed with real bytes.
- [The device recording generates App Attest keys that are never deleted] → App Attest has no delete API,
  and a stray key costs nothing. The contract never touches the production `AttestStore` items, whose
  addresses are the defaults. The clauses' addresses derive from the clause id.
- [Stricter doubles break a test that relied on the lie] → Tasks run the whole `jvmTest` and simulator
  suites. `World` pairs the doubles, and `DeviceAttestationTest` uses its own inline seams.
- [Renaming a port ripples into generated diagrams and CLAUDE.md's module map] → Tasks regenerate
  `architecture/` and edit the map's `:adapter:generic:app` and `:domain` lines.
- [`ProtectedStorage`'s one clause is thin] → It is the only honest clause available. Adding a
  fake-reachable `LOCKED` clause is exactly what the rule forbids.

## Migration Plan

Behaviour-preserving throughout. The port rename lands together with its only production caller
(`SnapSyncRoot`), so no intermediate state ships. Rollback is a revert. The recordings are the one
artefact that needs the phone: take the `ios-device` lease, install the rig build (`snapsync-device`),
`POST /contract/AttestKey` and `/contract/AttestStore` over the rig (`rig-channel`), and commit both
bodies unedited.

## Open Questions

None open. The one this change started with was settled by the device recording (SE2, iOS 26.6.2,
2026-09-23): `generateAssertion` with a key App Attest never generated answers an error
(`com.apple.devicecheck.error` code 3), exactly as `attestKey` does. So `AN_UNKNOWN_KEY_CANNOT_ASSERT` stays
a refusal clause.
