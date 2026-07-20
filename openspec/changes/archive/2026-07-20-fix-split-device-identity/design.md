## Context

On 2026-07-20 an SE2 enrolled in an event showed "pending" indefinitely and duplicated a photo in
the user's library. Two faults were present; only the second is this change.

The first was a **ledger schema downgrade** — a dev sideload carrying migration 11b took the
App-Group `ledger.db` to schema v5, then TestFlight build 426 (which predates 11b, schema v4)
installed over it. SQLiter's `migrateIfNeeded` refused the newer database, and the extension
fail-looped 390 times in 80 minutes. `4.sqm` predicted this verbatim. Build 428 contains 11b and
the error stopped; nothing here addresses it.

Underneath it was the fault this change fixes. With the extension running cleanly, the app held
device id `FD82A0DB…` and the extension held `DD92FAC9…`, consistently, across four events.

**What is established.** `IosKeychain` sets no `kSecAttrAccessGroup` on any operation. A diagnostic
built for this investigation showed both processes resolving `via=read(accessibility=ck)` — both
**found** an item, neither minted, with the correct protection class — while carrying *identical*
signed entitlements (`keychain-access-groups = ["E9Z8BADH58.app.snapsync.shared"]`, verified with
`codesign -d`). One `(service, account, accessGroup)` triple holds at most one item, so two
successful reads returning different values proves **two items exist in two different access
groups**. That deduction is the entire load-bearing basis of this design.

**How they got there.** Placement defaults to the first entitled group *at write time*, which is a
property of the signing entitlements of the build that happened to write the item. The ssh-mac dev
re-sign resolves entitlements out of the provisioning profile, and every Apple **development**
profile grants the wildcard `E9Z8BADH58.*` (verified by decoding both profiles) — Apple cannot know
which concrete groups are intended, because keychain groups need no portal registration. A wildcard
is not a writable group name, so writes fall back to each process's own `application-identifier`
group. This is the same defect class as the `associated-domains` wildcard already documented in
`CLAUDE.md`; that one was narrowed by hand in July and `keychain-access-groups` was not.

**Why it duplicates photos.** `DownloadController` skips an asset only when
`asset.deviceId == myDeviceId`. Uploads are stamped with the extension's id; the app compares
against its own. Measured end to end in 21 seconds: upload completes 07:59:33 → "1 foreign planned"
07:59:46 → re-imported as a new asset 07:59:54 → gallery resource count 2 → 4.

**Why it stayed hidden for nine hours.** Nothing logged either id. Every read reported success. The
only outward signals were an indefinite "pending" and duplicated photos, neither of which points at
identity.

Two prior hypotheses — that the signed appex lacked the shared group, and that a protection-class
read was silently minting — were both **falsified by measurement**. That history is why this design
avoids depending on any unverified inference about *which* group currently holds which id.

## Goals / Non-Goals

**Goals:**

- One device id observed by both processes, by construction rather than by entitlement ordering.
- Recover already-split installs without minting a third identity or destroying an existing one.
- Make a divergence visible from the device diagnostic log alone, with no instrumented build.
- Stop the dev re-sign from manufacturing new split identities.
- Keep correctness independent of which access group currently holds which id.

**Non-Goals:**

- Pinning the other four Keychain items. The attest pair demonstrably works cross-process (the
  extension cannot self-attest, so it is already reading the app's token); the album map is a
  self-healing cache that only the app writes; `KeychainConfigReader` **must** stay unscoped,
  because its purpose is finding an item an older build left anywhere.
- Removing photos already duplicated. `deleteAssets` always raises a system confirmation, so no
  silent cleanup exists.
- The extension's fail-loop and its `cycle finished — COMPLETED` on cycles that created no jobs.
  Real, independent, deferred to its own change.
- The ledger schema-downgrade hazard.

## Decisions

### D1 — Address the access group explicitly, rather than fixing the entitlements

Entitlements were already correct on the TestFlight build, and the split still occurred; the
experiment that gave both binaries one identical concrete group **still** produced two ids. The
defect is that placement is implicit, so the group in force depends on build history. Naming the
group makes placement a property of the code.

*Alternative rejected:* rely on the declared group being first in the entitlement. That is exactly
what `KeychainDeviceIdentity`'s doc already claimed — *"the item lands there by default and the
upload extension reads the same id"* — and the device falsified it.

### D2 — Add the group as an `IosKeychain` parameter defaulting to today's behavior

Only the device id opts in. A blanket pin would silently relocate `KeychainConfigReader`'s search to
the one place a legacy item may not be — resurrecting the "config reads as *no event joined* → the
extension concludes the device left → the join marker is cleared on every locked wake" failure that
`Keychain.kt` was written to kill.

*Alternative rejected:* pin globally in `IosKeychain`. Better guarantee, but it moves four items we
have not traced, one of which is load-bearing legacy archaeology.

### D3 — Strict resolution order, adoption before minting

`Unavailable` → error · shared `Found` → use · `Absent` → unscoped read → adopt · else mint.

Unavailability outranks everything: without that, a locked-device read falls through to the unscoped
search and then to mint, which is the original locked-device bug. Adoption before minting is what
makes the change safe under **every** placement — including the case where the shared group turns
out to be empty, where a naive pin would mint a *third* identity.

This ordering is why no probe of the current placement is required. The measurement that would have
told us which group holds which id does not change a line of this design; the existing diagnostic
reports the answer on the first launch after the fix.

### D4 — Adopt by writing into the shared group, not by moving the item

`write()` is `delete()` then `SecItemAdd`, which looked too destructive for an unrecoverable value,
and the symmetric alternative was a `migrateAccessGroup()` sibling of the existing
`migrateAccessibility()` using `SecItemUpdate`.

It is unnecessary. Once the group is explicit, `delete()` is **scoped to the shared group**, which in
the adoption branch is empty — it deletes nothing, and the out-of-group item is never touched. So
adoption is an ordinary scoped write, the legacy item survives as a rollback path, and we avoid
depending on an unverified claim that `SecItemUpdate` can move an item between access groups. Per
the repo's own law, that claim would have needed a forcing proof we do not have.

### D5 — The app mints; the extension defers

The extension cannot distinguish "no identity yet" from "the app's identity is not visible from
here", and guessing produced this bug. It reads the shared group and nothing else — notably it does
**not** perform the unscoped read, or it would find and adopt its own stale id and re-create the
split. On absence it skips the cycle and logs. The app resolves on every launch, so the stall is
bounded by one foreground.

### D6 — The app's id wins, and the resulting re-import is accepted

The extension adopts the app's id, so bytes previously uploaded under the extension's id stop being
recognised as this device's own and are re-imported once — one duplicate per already-uploaded photo.

*Alternative considered:* let the extension's id win, which would cost nothing here, since the
uploaded bytes and the event union already reference it. Rejected because the app's id is what
backend membership and push registration are registered under, and because the installed base is
internal TestFlight only — effectively one device — so the re-import is cheap. Note this cost is
**unavoidable once the ids converge**: whichever side changes, its prior uploads change owner.

### D7 — Fix the dev re-sign at the class, and assert the class

Build entitlements from the repo's own `.entitlements` files with `$(AppIdentifierPrefix)`,
`$(ASSOCIATED_DOMAIN)` and `$(APS_ENVIRONMENT)` expanded, plus `get-task-allow` (the one thing the
profile-resolve supplied for free). The repo files *are* the claim; the profile is only a grant, and
copying a grant into a claim is the category error behind both wildcard bugs.

Independently, assert that **no wildcard survives** into a signed binary. That check needs no
knowledge of which keys are wildcard-shaped — the knowledge the narrowing approach depends on and
kept not having — and would have caught both the July `associated-domains` bug and this one at
signing time.

## Risks / Trade-offs

- **The two-groups deduction is wrong** → Everything rests on D1's inference from two successful
  reads returning different values. If it is wrong, the fix does not converge the ids — but the
  strict ordering still cannot mint a third id or delete an existing one, and the diagnostic reports
  the failure on the first launch.
- **Adoption picks the wrong id on an install we have not seen** → The app adopts whatever its
  unscoped read returns, which spans only groups the app is entitled to; it cannot reach the
  extension's private group. Worst case is that it adopts its own older id — still a single id
  shared by both processes.
- **One-time re-import of prior uploads** → Accepted per D6; unavoidable once ids converge. Bounded
  by the already-uploaded set and self-limiting (the extension suppresses re-upload of downloaded
  resources, verified in the field at 07:59:55).
- **Extension stalls if the app never launches** → Bounded by one foreground; the app resolves
  identity on launch. Made visible by the mandatory skip log.
- **Keychain cannot be unit-tested** → A Kotlin/Native test binary is not an app bundle, so
  `securityd` refuses it (`errSecNotAvailable`, −25291). The resolution *ordering* is pure and
  testable in `commonTest` against a fake `Keychain`; actual placement is verifiable only on device,
  via the two diagnostic lines agreeing.
- **The access group hardcodes a team prefix** → Mitigated by the guard composing it from `TEAM_ID`
  and the entitlements' declared suffix, so the three cannot drift apart silently.

## Migration Plan

1. Ship the change. On first app launch it resolves the id into the shared group — reading it if
   already there, adopting the out-of-group value otherwise.
2. The next extension invocation reads the shared group and observes the same id.
3. Verify on device: pull both `Documents/debug.log` files and confirm the two `deviceIdentity`
   lines report the **same** id. An extension line reporting `minted`, or two different ids, means
   the model is wrong — investigate rather than proceed.
4. Expect one re-import wave of previously-uploaded photos (D6), then steady state.

**Rollback:** revert the binary. The out-of-group item is never deleted, so a reverted build finds
its original id exactly where it left it. The adopted copy in the shared group is inert to a build
that does not name the group — worst case it is the same value.

## Open Questions

- Which access group currently holds each id? Deliberately **not** resolved: D3 makes it irrelevant
  to correctness, and the diagnostic answers it on the first launch after the fix.
- Should the wildcard assertion also run in `ios.yml` against the distribution-signed appex? Nearly
  free and guards the artifact that actually ships, but CI signs from the entitlements file and so
  is not the path that produced this. Left out of the spec surface (ssh-mac is non-gating dev
  infrastructure with no spec) and open as a judgement call at implementation.
- Do the other four Keychain items warrant the same treatment once traced? Out of scope here (D2);
  the attest pair's cross-process read is evidence they are currently placed correctly.
