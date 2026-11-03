# Design

## Context

See proposal.md — Why. Today the upload request is minted with the credential's in-memory token when one is held and
without an `Authorization` header otherwise (`BackendUploadRequests.mint`); a retry reads the store of record. A
token-less request goes out, the edge answers `401`, and the failure is retried forever. Nothing upstream stops it: the
cycle's entry gate reads no credential, and the app's prelude refreshes the attestation as an isolated step.

The backend gateway already knows two kinds of request: an **open** one, sent with a token when held and without one
otherwise, and a **gated** one — a create, a join, the push registration — which, holding no token, asks the credential
for one (`Credential.missing()`: the app attests on the spot; the upload extension's `DropOnlyCredential` cannot, and
answers none). An upload is a gated request in everything but its code path.

When the extension holds no token: `DropOnlyCredential.rejected` drops a token the backend refused (on a gated call such
as the manifest publish) and can never mint another, so from then until the app's next wake re-attests, every extension
pass sends token-less uploads. Rarer: the token slot cannot be read while the device id can.

Phase 4.4b moves the request builder and both transfer tiers into the data layer (its target: `UploadRequests` answering
`RequestBuilt`), which is where this lands.

## Goals / Non-Goals

**Goals:**
- No upload request — a first create, a re-created failure, or the re-point of an OS-offered retry, on either tier — is
  built without a held proof.
- What waits is not lost and leaves no residue: the row stays exactly as the next verified pass needs it.

**Non-Goals:**
- Judging a held token's expiry on the device (the backend stays the one judge — D4).
- The outdated app (`app-update-required`) and any other refused-by-the-edge case.
- The status line's wording (sync-status already says sharing is paused while the device cannot be verified).

## Decisions

### D1 — An upload is a gated request

The request builder takes its token the gated way: held → built with it; none → `Credential.missing()`, and built if that
yields one; still none, or the slot unreadable → `RequestBuilt.NoToken`. Nothing is minted for a request that cannot carry
a proof, so no tier can send one.

*Alternative rejected:* withhold at the cycle's entry gate. The gate would have to read the credential, and a token can be
dropped mid-pass (a refused publish inside the same invocation) — the request is the one place that knows what it sends.

*Alternative rejected:* keep sending and drop the bytes at the edge faster. The bytes still leave the phone, which is the
promise this change makes.

### D2 — A withheld create ends the pass and records nothing

`NoToken` from a create leaves the row `DISCOVERED` (write-after-act: no job, no record) and ends the creation pass, since
every later row would withhold for the same reason. The pass answers "withheld", not "work left":
- the **extension** answers the OS `COMPLETED` and rests. It cannot obtain a proof, so asking to be re-invoked would only
  repeat the refusal; the OS invokes it again on the next library change, and the app — which uploads under any usable
  grant — creates what waited once it is verified.
- the **app** keeps the heartbeat's "work left", so its next wake (which attests in its prelude) creates the jobs.

### D3 — A withheld retry leaves the OS's job alone

The re-point of an OS-offered retry (iOS ≥ 26.1) with no token is skipped: the job stays offered, its row stays
`REQUESTED` (iOS still holds the job), and the next invocation presents it again. A refused-then-terminal job returns
through the drain as any failure does, and its re-creation is a create (D2).

### D4 — A held token is sent, expired or not

The device does not judge expiry: its clock can be wrong, the backend's rule would be duplicated, and "A renewal fails
while the proof is still valid" requires a still-valid proof to keep working. An expired token is sent, refused, and
retried as today — the photo is held, never lost (`TrustIntegrationTest`'s expired-proof scenario stays green unchanged).

### D5 — Implemented in the moved request builder

The fix lands in phase 4.4b's data-layer `UploadRequests` (after the ledger and transfers move), not in today's
`BackendUploadRequests` — writing it in code the same pull request deletes would be written twice. The integration test is
written first and fails against today's code.

## Risks / Trade-offs

- [The extension rests while photos wait] → The app uploads them under any usable grant once verified; the wait is bounded
  by the member's next opening or the app's next wake, which is also when the proof can be renewed.
- [`missing()` attests in a background wake of the app] → Already the gated path's behaviour for publishes and pushes; no
  new attestation trigger is added.
- [A token dropped between the build and the send] → The request carries the token it was built with; the backend refuses
  a stale one as today (D4).

## Migration Plan

None: no stored state changes. Rollback is reverting the commit; a token-less row simply uploads again as before.
