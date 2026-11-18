# Design

## Context

The api's Android verifier (`api/src/android-attest.ts`) checks the chain before anything else. A chain whose root is
not one of the deployment's pinned Google roots fails there with a plain error. The route answers that error with the
default reason, `device-unverifiable`, and the detail `certificate`. The later check that names a software key
`device-modified` (security level 0) never runs, because those chains never get past the root check.

Commit `cd7791966` added the recognition this change builds on. Under the `hardware` trust that every deployment uses,
an unpinned root is classified before the plain error: AOSP's software attestation roots match by public key, and an
emulator's self-signed "Droid Unregistered Device CA, O=Google Test LLC" root matches by name. Either one throws
`AttestationRejected("device-unverifiable", …, "software-root" | "emulator-root")`. The detail is diagnostics only.

On the app side, the reason the 401 body names drives everything the user sees. `IssuerReplies` parses it into
`DeviceRefusal`, presentation maps it to the screen's message, and only `DEVICE_UNVERIFIABLE`'s message offers the
report (`offersReport`). Every shipped build since the refusal reasons landed already understands `device-modified`.

## Goals / Non-Goals

**Goals:**
- The two software-only roots are refused with the modified reason, so the app shows the modified cause with no report
  offer.
- Every other unpinned root keeps the default reason and its report offer.

**Non-Goals:**
- No app change. Any copy change for the modified message is a separate change.
- No change to what is admitted.
- Recognising other software-only shapes, such as a factory chain whose leaf says security level 0 under a pinned
  root. That one already gets `device-modified` from the security-level check.

## Decisions

**1. Change the reason where the roots are recognised, keep the detail.** The `AttestationRejected` thrown for a
software-only root carries `device-modified` instead of `device-unverifiable`, and keeps `software-root` /
`emulator-root` as its detail. The 401 body becomes e.g. `attestation rejected: device-modified (emulator-root)`.
- *Alternative:* map the detail to a reason in the route. Rejected: the reason belongs to the check that decided it,
  as for every other `AttestationRejected`.
- *Alternative:* let these chains reach the security-level check. Rejected: that would mean skipping the root check
  for them, and the root check's failure is what makes the refusal safe.

**2. "Modified" is the cause to tell, not a fourth one.** The causes are a closed set the user is told
(`RefusalReason` / `DeviceRefusal`). A new cause would need every shipped app to learn it, and an older app reads an
unknown reason as `device-unverifiable`, which would bring back exactly the report offer this change removes.
`DeviceRefusal.DEVICE_MODIFIED`'s own KDoc already says "a key held only in software".

**3. Match the emulator root by name.** Its key is minted per AVD (measured 2026-09-29, recorded in the verifier's
header), so there is no key to pin. Forging the name only earns a forger a different refusal, never an acceptance.

## Risks / Trade-offs

- [A real phone with erased factory keys, after a repair or a reflash, is told its system is modified although its
  owner may have done nothing] → It is refused today as well, and nothing on our side can ever admit it: its proof is
  indistinguishable from a forged one. The modified wording ("runs its maker's system") is the nearer truth for
  such a phone. No report from one has been seen in 134 refusals.
- [Losing the count of real phones in that state] → The api's `rejected=` log line and its detail still record every
  one, by root.
- [An older app version] → Unaffected: every shipped build already maps `device-modified`.

## Migration Plan

Server-only and stateless: it ships with the next api deploy, and a rollback is a redeploy of the previous bundle.
After the deploy, resolve SNAPSYNC-45 and SNAPSYNC-65 (diagnostic dumps → `resolve/`) through the `Bugsink-Resolves:`
trailer.
