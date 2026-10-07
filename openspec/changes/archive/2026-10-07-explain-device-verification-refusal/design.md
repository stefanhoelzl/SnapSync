# Design

## Context

The motivation is in proposal.md (Why). The current code path, end to end:

- **Backend.** `POST /attest/token` (`api/src/routes/attest.ts`) catches every verifier throw and answers
  `401 "attestation rejected"` on both versions. The reason exists only in `console.error`, as the verifier's free
  text. The Android verifier is `api/src/android-attest.ts` and the App Attest verifier is `api/src/attest.ts`
  (`verifyAttestation`). `api/test/attest.test.ts` already pins "v2 answers a rejected attestation 401, exactly as
  v1 does" (status only).
- **Classification.** `HttpBackend` already hands the raw body through as `Reply.Refused(status, body)`.
  `tokenRefusal()` (`DeviceAttestation.kt`) reads `stale challenge` and `not attested`, and maps every other 4xx to
  `TokenOutcome.Refused`, which carries no payload. `refreshLocked` logs the failed mint and returns `false`, so the
  reason is lost there.
- **Surfacing.** The only verdict the UI sees is `DeviceAttestation.attested: StateFlow<Boolean>`. It is cleared on
  entry to every `refresh()` (SNAPSYNC-20, archive `2026-08-25-correct-attestation-health-surfacing` D2/D4), and it
  is read only inside the joined `Membership`, never by the unjoined layers.
- **Create.** `EventRegistry` maps every non-400 refusal, `401` included, to `CreateOutcome.Transient`, which shows
  as `CreationFailureReason.SERVER` → `ScreenMessage.CREATE_FAILED` ("Couldn't connect").
- **Join.** `BackendEventJoin` maps `401` to `JoinResult.FAILED` → `JoinOutcome.EnrollFailed` →
  `Step.CommitFailed` ("Couldn't join / Something went wrong").
- **The 401 retry.** `CredentialedBackend.gated` re-attests on a `401` *only if a token was sent*. A device that
  never obtained a token sends none, gets its `401` back unretried, and so a tap on such a device never re-attests.
- **Bug-report sheet.** `AppTextPromptSheet` already takes `PromptField.initialValue`, but it treats text equal to
  the initial value as "unchanged" and refuses to submit it. That rule exists for rename.
- **Mocks.** No lever makes the mint refuse. The only way to an unattested device today is
  `DeviceIntegrityMock(available=false)`, which is fixed per launch.

## Goals / Non-Goals

**Goals:**
- A refusal carries its reason from the verifier to the screen, with one closed vocabulary end to end.
- Every new state is reachable in the world harness and over the rig through a lever, never forged.

**Non-Goals:**
- The backend's rejection log line naming the chain's root. That is internal work that ships separately; this change
  gets the operator the same facts through the user's report (D11–D13).
- Telling the user WHICH certificate problem it was. The `certificate` code (D10) and the chain summary are
  diagnostics only: no finer cause gives the user anything different to do.
- Changing *what* is refused. Every check stays as it is.
- Telling the user about a local proof failure, where the phone's keystore throws and no request is sent (see Risks).
- A store button. "Points the user to the official store" is met by the sentence itself.

## Decisions

**D1 — Three reasons, a closed set, platform-neutral.** `device-modified`, `device-unverifiable` and
`app-not-genuine`. The backend's verifiers throw a typed `AttestationRejected(reason, detail)`. Any untyped throw
(DER parsing, a library error) defaults to `device-unverifiable`, because that is the one reason that never accuses
the user. Mapping:
- Android `device-modified`: a software attestation under hardware trust; boot not verified or bootloader unlocked.
- Android `app-not-genuine`: no application named; not this package; signing certificate not accepted.
- Android `device-unverifiable`: everything else, including an unpinned root (the OnePlus case), a wrong chain
  length, issuer and signature failures, validity, revocation, a challenge that is not ours, an unknown security
  level, and a key that is not P-256.
- Apple `app-not-genuine`: `rpIdHash` is not this app.
- Apple `device-unverifiable`: everything else. Apple has no `device-modified` signal.

*Alternatives:* the verifier's own text (it couples the client to log prose); one generic code (cannot avoid
accusing a genuine phone).

**D2 — The wire is plain text, under v2 only.** v2's body becomes `attestation rejected: <reason>`. v1 is frozen
and keeps `attestation rejected`. Plain text matches every other refusal body on these routes. Older builds compare
the body only against `stale challenge` and `not attested`, so to them any other 4xx is still `Refused` and nothing
changes. The client parses the suffix in `tokenRefusal()`; an unknown or missing suffix reads as
`device-unverifiable`. `docs/architecture.md`'s `/attest/token` row documents the body.

**D3 — `TokenOutcome.Refused` carries the reason, and `DeviceAttestation` publishes the latest verdict.** It
becomes `Refused(reason: DeviceRefusal)`. `DeviceRefusal` is a model/ enum of the three reasons. A local proof
failure, where no request was sent, gets its own case, `TokenOutcome.ProofFailed`. It is handled as `Refused` is
today: attest afresh after a renewal, log after a mint. No reason is told for it. `DeviceAttestation` gains `refusal: StateFlow<DeviceRefusal?>` with exactly
`attested`'s bracket: cleared on entry to `refresh()`, and set at its end to the mint's reason when the attempt
ended in a service refusal, `null` otherwise. Using the same bracket means a refusal from an earlier wake is never
the first frame of a later foreground, the lesson of SNAPSYNC-20.

*Alternative:* a refusal that is sticky until a success. It avoids the clear during a tap's re-attest, but it
reopens SNAPSYNC-20's stale-verdict class. During that clear, create and join show their own in-flight state anyway.

**D4 — A tapped call with no token obtains one first.** `Credential` gains `missing()`, and
`CredentialedBackend.gated(obtainFirst = true)` calls it before sending when no token is held. Only create and
join, the two calls a user taps, pass `obtainFirst`. The app's `Credential` refreshes, and that refresh also
publishes its verdict, so the screen learns of a refusal from the tap itself. The extension's `Credential` keeps
the default `null`, which is today's behaviour. This is what makes "each tap re-checks first" true on a device that
has never attested, which is exactly the refused phone. A device with a token is unchanged: its `401` path already
re-attests and retries once.

*Alternative, rejected during apply:* obtain first on every gated route. A refused phone would then re-attest once
per background call (the push registration at every entry, every upload cycle), for no user benefit.

**D5 — Create and join learn "refused" from the route, and the cause from the verdict.** The `401` from
`/events` or a join carries no reason; only the mint does. So:
- `EventRegistry` maps a `401` to a new `CreateOutcome.Unverified`, which becomes `CreationFailureReason.UNVERIFIED`.
- `BackendEventJoin` maps it to `JoinResult.UNVERIFIED`, which becomes `JoinOutcome.Unverified` and then a new
  join step, `Step.DeviceRefused`.
- Presentation combines the failure with `refusal` to pick the message. With no refusal known (the attempt got no
  answer), create falls back to `CREATE_FAILED` and join to `CommitFailed`. This is the spec's "no answer is not a
  refusal".
- `ScreenMessage` gains `DEVICE_MODIFIED`, `DEVICE_UNVERIFIABLE` and `APP_NOT_GENUINE`; the three `ScreenMessage`
  values are the only place copy is chosen.

**D6 — The front screen reads `refusal` directly.** `StatusSources` passes `refusal` to the unjoined reduction.
`Layer.CreateEvent`'s line below Create shows, in order of priority:
1. the network notice;
2. a create failure (which, for a refused phone, is the refusal itself);
3. the standing refusal;
4. the scan hint.

Create stays enabled.

**D6a — One `DeviceVerification` source (apply-time).** `StatusSources` takes `attested` and `refusal` as one bundle,
`DeviceVerification`, rather than two parameters: the core tier's `LongParameterList` ceiling (15) may only fall,
and the two halves share one bracket anyway.

**D7 — The joined line names the cause and stays non-tappable.** `SyncHealth.Unattested` becomes
`Unattested(refusal: DeviceRefusal?)` (serialisable, so the wire `UiState` carries it), and
`AppSyncStatus.CannotVerifyDevice(cause)` picks the title by cause. Its priority and gating stay `attested`'s,
unchanged. There is no Report button on this line: that would also mean changing "Only missing access and a blocked
network make the status line tappable", and the menu's "Report a problem" already reaches the same sheet.

**D8 — "Report this" seeds the existing sheet.** The intent is `UiIntent.ReportRefusal(message: ScreenMessage)`, beside
the unchanged `ReportBugOpen` (corrected after apply, where a separate intent kept the menu and the gesture untouched).
`Overlays` holds the seed beside `reportingBug`, and the UI resolves it to the localised sentence as the
`initialValue`. `PromptField` gains `submitUnchanged: Boolean`, so a seeded report can be sent as is; rename keeps
`false`. The report's logs already carry the refusal reason (D3's log line names it), so the operator does not
depend on the note. Only the could-not-be-verified notice on the front screen and the join screen offers the button.

**D9 — A mock lever for each reason.** (Apply-time: while the lever is set, EVERY gated call answers `401`, whatever
token it carries, rather than only tokens that predate it — simpler, and it reaches the same states.) The mock gets `BackendOperator.refuseAttestation(reason: DeviceRefusal?)`,
persisted in `MockState` the way `refuseNextCredential` is. While set, `InMemoryBackend.mintToken` answers
`Refused(401, "attestation rejected: <reason>")`, and every gated call carrying a token that predates the lever
answers `401`. The rig verb is `backend/refuse-attestation?reason=…|off`, added to `RigVocabulary`. The world
inspector gets a selector next to its other backend failure levers.

**D10 — One `certificate` code, v2 only.** When a CERTIFICATE check fails (every throw in `verifyChain`, and a
revoked certificate), v2 answers `attestation rejected: device-unverifiable (certificate)`. The verifier tags those
throws as it tags the other two reasons (`AttestationRejected` gains an optional `detail`). Every other failure carries
no code. The client parses the bracketed code in `tokenRefusal()`, and `TokenOutcome.Refused` gains `detail: String?`.
The code is never shown to the user: it travels to the report (D13) and the log.

*Alternative:* one code per check, rejected in the interview. The chain summary says which certificate problem it
was, and one code keeps the wire vocabulary small.

**D11 — The chain summary is built where the chain is.** `AndroidDeviceIntegrity.prove()` holds the Keystore's
`X509Certificate`s before it concatenates their DER. It summarises each certificate above the leaf: its subject and
issuer as RFC 2253 names, `notBefore` and `notAfter`, and its key algorithm and size. It also computes the SHA-256 of
the root's encoded public key. `Proof` carries the summary as a list of plain facts (model/), `null` where there is
none: renewal assertions and App Attest (iOS). The device-integrity mock's fresh proof carries a fixed summary
(`MOCK_CHAIN`), so the offered report is reachable through the rig. The leaf is left out: it is the app's own key, and its key
description is not part of the agreed report. Serial numbers are not read as a field, but names are kept verbatim.

*Apply-time finding:* on the emulator, the remotely provisioned attestation certificate is NAMED after its own serial
(`CN=3c68c85c…,O=TEE`), so a verbatim name carries a serial. Decided not to redact it: serials are not secret, the
backend already receives every chain with every attestation, the report is the user's to send, and a serial is what
Google's revocation list is keyed by. The spec's "no serial numbers" was dropped accordingly.

**D12 — The facts live in memory, beside the verdict.** `DeviceAttestation` keeps the last refused mint's
`RefusalFacts(reason, detail, chain)`. A successful attestation clears them. Unlike `refusal` (D3), they are NOT
cleared at the start of a refresh, because a report opened while one runs must still find them. They are never
persisted: a relaunch re-attests at launch, and that refusal captures them afresh.

**D13 — Only an offered report carries them.** A sheet opened with a seed (D8) sends `SendDiagnostics` with a
verification flag. Only that dump adds `attest_failure` (the reason and code as the backend sent them),
`attest_chain` (one line per certificate) and `attest_root_key_sha256` to its state section. Menu and gesture reports
never carry them. The summary is under 1 KB, well inside the dump's bounds (`model/EventBounds.kt`).

## Risks / Trade-offs

- [Naming which check failed tells an attacker which check stopped them] → The checks are standard and public
  (Google's and Apple's documented verification steps). The three buckets are coarser than the checks, and the
  precise text stays in the log only.
- [A genuine phone is shown "could not be verified" for our bug, e.g. a missing root] → That is the case the reason
  is worded for: it never blames the user, it offers a report, and the internal root-logging change lets us pin a
  missing root. D4 then means the next tap heals.
- [A local proof failure, where the keystore throws, is told as "couldn't connect"] → It is rare and sends no
  request. It is left as today, with its own log line, and revisited if a report shows it.
- [D4 adds an attestation attempt in front of a tapped call on a tokenless device] → Such a call would `401`
  anyway, `refresh` serialises on its lock, and background routes never take this path, nor does the extension.
- [A certificate's name can carry a serial — a factory intermediate's `serialNumber` attribute names its keybox, and an
  RKP attestation certificate is named after its own serial] → Accepted: in the interview for the keybox, at apply
  time for the RKP name (D11). A keybox is provisioned to a batch of devices, not one, and the report is sent only by the user, who
  already sends the app's own device id.
- [A chain summary from a spoofed keybox shows whatever the spoofer wrote] → It is diagnostic text read by the
  operator, never trusted: the verdict is the backend's.
- [Changing the shape of `Unattested` (D7) breaks `UiState` wire compatibility with a mirror on an older build] →
  The rig and harness are built from the same tree; `UiStateSerializationTest` is updated alongside.

## Migration Plan

The backend ships first: v2's new body is harmless to every shipped client (D2). The app follows in any later
merge. There is no data migration. Rollback is a revert on either side: a client that receives the old body reads
`device-unverifiable`.
