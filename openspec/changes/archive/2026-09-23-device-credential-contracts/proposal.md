## Why

Three in-memory doubles that the world harness composes into every run have no contract behind them:
`InMemoryAttestKey`, `InMemoryAttestStore` and `InMemoryProtectedStorage`. A fourth, `InMemoryAttestClient`,
is contracted only for `challenge()`. Two of these doubles already say things the real implementation
never would. `inMemoryAttestKey(supported = false)` still hands out keys, while the real `IosAttestKey`
refuses on an unsupported service. `InMemoryAttestClient` mints a token for any bytes at all, while the real
edge refuses a forged attestation. The push registration's HTTP seam went uncontracted in phase 7 (its D7)
because it is a generic by-URL transport, and half of it (`post`) is now dead code. This is phase 8c of
the testing-concept sequence: the ports the handoff grouped as "device-only". Checked against the tree,
only App Attest's key half actually needs a device.

## What Changes

- **`AttestKey` contract.** The `UNSUPPORTED` state runs **live on `IOS_SIM_KEXE`**, where
  `DCAppAttestService.isSupported` is false. The existing refusal assertions in `IosAttestKeyTest` become
  clauses. The `SUPPORTED` state is **recorded on `IOS_DEVICE_APP`** and replayed on every build.
  `IosAttestKey` routes its `DCAppAttestService` calls through a new `internal` seam in
  `:adapter:ios:ext-safe`, following the `KeychainApi` pattern. This refactor preserves behaviour. The replay
  checks what the edge's verifier depends on: the adapter hashes the challenge into the `clientDataHash`
  that it hands the platform.
- **`AttestStore` contract.** It runs over the `KeychainApi` seam that `SecureStore` already records:
  `INACCESSIBLE` live on `IOS_SIM_KEXE`, readable states recorded on `IOS_DEVICE_APP` in the same device
  session as `AttestKey`. It pins down the two promises the doubles rely on. An unreadable store throws and
  is never read as "not attested yet". `clearToken` keeps the `keyId`.
- **`AttestClient` refusal clauses.** These run on `JVM` against the live edge and bind the in-memory
  double: a forged attestation is refused, a challenge the edge never issued is refused, and a device that
  never attested cannot renew. Successful mint and renew stay out of the contract, with the reasons written
  down. A recorded attestation cannot be replayed, because the challenge is HMAC-signed with a 300 s expiry
  and the certificate chain is checked against the current time. `api/test/attest.test.ts` already verifies
  success against a real Apple fixture with a pinned date.
- **The fakes are made honest where a clause shows they lie.** `InMemoryAttestKey` refuses when unsupported
  and refuses a key it never generated. `InMemoryAttestClient` mints only for an attestation of the shape
  `InMemoryAttestKey` produces, over the challenge it issued. The world's attested path already produces
  exactly that shape, so its runs are unchanged.
- **BREAKING (internal port): `PushHttpClient` is replaced by a need-named `PushTokenPublisher`**
  ("publish this device's push token"). Building the URL and body moves out of `PushRegistration` into the
  adapter, `HttpPushTokenPublisher` in `:adapter:generic:app`. That adapter replaces `KtorPushHttpClient`,
  and the dead `post` goes with it. `PushRegistration` keeps its policy: it absorbs failures, retries on the
  next token, and re-registers when the credential changes. **`PushTokenPublisher` contract** runs live on
  `JVM` against the real `api` and on the mini-edge as the `Fake`. It closes phase 7's D7 the way D7 named.
- **`ProtectedStorage` contract.** One clause runs **live on `IOS_SIM_APP`** through the rig's simulator
  registry: a running, unlocked app reads protected storage as readable. The locked state stays excluded,
  as `port-contracts` already records. That exclusion is now also written, with its reason, on
  `IosProtectedStorage`.
- **`ProcessMetricSource`: a documented exclusion, not a contract.** No in-memory double exists, so there
  is nothing to back. The OS delivers reports when it chooses and only once, so no host can enter a state on
  demand. The measured evidence already lives on `MetricKitProcessMetricSource`, and this change adds a
  statement there that it has no contract and why.
- **No contract needed:** `PushTokenSource` is a concrete settable holder, not a port with an adapter.
  `PushReceiver`'s implementations are domain features. The OS entry into both is already covered by
  `PlatformEntriesContract`.

## Capabilities

### New Capabilities

None. The contracts are code. `port-contracts` specifies the mechanism, and no spec restates clauses.

### Modified Capabilities

- `push-registration`: "Token registration writes the device config". The request goes out through the
  need-named `PushTokenPublisher` port, whose adapter builds the `PUT <host>/devices/<deviceId>` and its
  body, instead of an injected generic HTTP client seam.
- `port-contracts`: "Replay matches exactly, in order, over deterministic clauses". A value the platform
  mints and the adapter sends back (an App Attest `keyId`) is masked the same way in requests and answers,
  so a replay feeds the placeholder back. Credential material the platform mints (an attestation, an
  assertion) is always masked, because recordings are committed to a public repository.

## Impact

- **Production code (behaviour-preserving):** a new `internal` App Attest seam in `:adapter:ios:ext-safe`
  that `IosAttestKey` goes through. `PushHttpClient`/`KtorPushHttpClient` are replaced by
  `PushTokenPublisher`/`HttpPushTokenPublisher`. `ApnsPushToken` moves to `:domain` `model/` so the port can
  name it. `PushRegistration` and `SnapSyncRoot` are rewired.
- **Test-only code:** four new contracts (`AttestKey`, `AttestStore`, `PushTokenPublisher`,
  `ProtectedStorage`) and three new `AttestClient` clauses in `:test:contracts`. Bindings sit beside their
  implementations. Two recordings, `AttestKey@IOS_DEVICE_APP.rec` and `AttestStore@IOS_DEVICE_APP.rec`, come
  from one rig session on the phone (device lease). `IosAttestKeyTest`'s refusal tests become clauses.
- **Doubles:** `InMemoryAttestKey` and `InMemoryAttestClient` become stricter. `World` and
  `DeviceAttestationTest` are checked to be unaffected.
- **Never touched:** the `snap-sync-dev` zone. No event is joined. The device session makes one real Apple
  attestation per recording, which is rate-limited, so recordings are retaken rarely.
- **Label `internal`.** No user-visible change. No module added.
- **Sequence:** 8d (`diagnostics-contracts`) is unaffected. `ProcessMetricSource` gets no contract here, and
  `DiagnosticsReporter.describeProcess` remains 8d's.
