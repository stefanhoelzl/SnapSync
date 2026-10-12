# Design

## Context

`encrypt-events-by-default` (archived 2026-10-08) made a production build mint a key for every event it creates:
`EventKeyMinting.forNewEvent()` mints unless `DevControls.createsPlainEvents()` answers `true`, and the production
adapter `InertDevControls` answers `false`. Its Migration Plan made the 0.6 promote wait for `MIN_APP_VERSION` 0.5,
because a 0.4 app's link decoder knows only `/join#` and reports the keyed path form as a damaged invite.

Facts that shape this change:

- Measured 2026-10-12 against the production database (read-only token): 5 `sharing`/`settled` memberships of 0.4
  devices in 2 open events (0.5: 8 in 1). Raising the minimum now answers those 5 devices `426` mid-event.
- 0.6 does not otherwise touch a 0.4 device: a plain event's invite is the fragment form
  (`StatusContainerHost` `EventConfig.inviteUrl`, `keyId == null`), and every api shim 0.4 needs stays until the
  minimum is 0.5 (`PENDING_CLEANUPS.md`).
- The rig and the mocks default to encrypted (`RigDevControls`, `DevControlsMock`: `createsPlainEvents() = !encrypts`,
  `encrypts` true), and the integration suite was made encryption-aware for that default (prior design D8).
- The Privacy Policy (`site/src/pages/index.astro`) already says "In an event created with SnapSync 0.6 or later,
  every photo is stored encrypted…"; the site deploys on every merge, so it is live now.

## Goals / Non-Goals

**Goals:**
- A production 0.6 build creates only plain events, so nothing it shares or shows is unopenable on 0.4.
- Re-enabling is small and fully recorded: one answer flipped back, the spec and the policy text restored.
- Every encrypted-event path stays built, tested and shipped, so a 0.6 phone handles an encrypted event (from a rig
  build now, from the enforcing release later).

**Non-Goals:**
- No revert of the encryption code (lost-key line, key restore, Play referrer `k`, incomplete-invite wall): it is
  inert without encrypted events and is what the enforcing release needs.
- No api change; `MIN_APP_VERSION` stays 0.4.
- Retiring plain events stays the later change it already was.

## Decisions

### D1 — The production answer flips: `InertDevControls.createsPlainEvents()` answers `true`

The single read that decides production creation is the inert adapter's answer; flipping it is the rollback the
prior design named. Alternative: a model-level product flag (`EncryptsNewEvents = false`) read by `EventKeyMinting`
beside the dev control — rejected for a temporary state: it adds a second decider that the re-enable would have to
remove again, and the prior design's objection (a "dev control" name deciding a product rule) is accepted for the
interval, recorded in `PENDING_CLEANUPS.md` so the re-enable restores the KDoc's "always `false`" promise.

### D2 — The rig keeps encrypting by default, so the rig and production differ on creation

`RigDevControls` and `DevControlsMock` keep `encrypts = true`. The integration suite therefore runs the encrypted
path, not the production default, for every create — the opposite trade-off of the prior D8. Accepted (user
decision): the encrypted path is what the next release needs proven, and the plain path is the one 0.5 already ships.
Plain creation stays reachable through `device/encrypt-new-events?on=false` (and is what
`PathFormLinkIntegrationTest`/`EncryptedEventIntegrationTest` already use for plain cases).

### D3 — `DevControlsContract`'s cells stay covered by a real host

The shipped clause (`SHIPPED_EVERY_CONTROL_IS_AS_SHIPPED`) now expects `createsPlainEvents → true`. Its `false` cell
would then be covered by no host (`ClauseCoversTest` requires every grid cell covered), and the switched clause
also answers `true`. The switched state therefore becomes "the channel switched every control away from the shipped
value": the rig's binding sets encryption **on** for `SWITCHED`, answering `false`. The binding change is in
`RigDevControlsContractTest`; the clause text and its KDoc follow. Re-enabling swaps them back.

### D4 — The Privacy Policy drops the encryption paragraph until the enforcing release

`privacy-security`'s "The Privacy Policy states what leaves the device" requires it to be accurate in every release;
"events created with 0.6 or later are encrypted" would be false. The paragraph (from "In an event created with
SnapSync 0.6 or later" through "…keeps neither the key nor the unencrypted photo.") is removed now and its text kept
verbatim in `PENDING_CLEANUPS.md`, to return with the version number of the enforcing release. Alternative: reword it
to "events created by a future version" — rejected: a policy describes what happens, not plans.

### D5 — `PENDING_CLEANUPS.md` carries the re-enable

- "The floor itself": deadline becomes "before the release that re-enables encrypted creation" (not "the 0.6
  promote").
- "At the 0.6 promote" becomes "At the promote that re-enables encrypted creation" (the site/listing claim).
- New section, unlock = `MIN_APP_VERSION` ≥ 0.5 merged: re-enable encrypted creation — flip `InertDevControls`, its
  test and KDoc, `DevControls`/`EventKeyMinting` KDoc, `DevControlsContract` (D3 back), the `invite-link` spec (this
  change's delta reverted, via its own OpenSpec change), the Privacy Policy paragraph (D4), `docs/architecture.md`,
  `CLAUDE.md`, the rig-channel skill wording. Ordered: floor merged → re-enable merged → claim merged → promote.
- "Once `MIN_APP_VERSION` ≥ 0.6 and no plain event is open" stays, but its unlock is moved behind the re-enable
  release rather than 0.6.

## Risks / Trade-offs

- [Rig ≠ production on creation] → a plain-create regression would surface only through the explicit plain tests and
  0.5/0.6 users, not the whole suite. Mitigation: the plain path is unchanged since 0.5 and has its own tests.
- [Encrypted events created by internal builds since 2026-10-08] → keep working on every 0.5+ build; a 0.4 guest still
  cannot open their invites — internal only.
- [Re-enable forgotten] → the `PENDING_CLEANUPS.md` entry is tied to the floor bump, which is the first step of the
  next release that needs it.

## Migration Plan

1. Merge this change; internal builds create plain events again.
2. Promote 0.6 with `MIN_APP_VERSION` 0.4 — no prerequisite.
3. Later: once the SQL check reads 0, bump the minimum to 0.5, then re-enable encrypted creation (its own change),
   then the claim, then promote that release.

Rollback: revert this change (production encrypts again) — only safe once the minimum is 0.5.
