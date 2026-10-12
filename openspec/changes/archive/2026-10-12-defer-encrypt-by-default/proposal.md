# Proposal

## Why

0.6 creates every new event encrypted (`changes/archive/2026-10-08-encrypt-events-by-default`), and an encrypted
event's invite is the keyed path form, which a 0.4 app reports as a damaged invite. The planned guard — raising the
api's minimum app version to 0.5 before the 0.6 promote — cannot happen yet: on 2026-10-12, 5 memberships of 0.4
devices sat in 2 open events, and raising the minimum cuts them off mid-event. Deferring encryption-by-default lets
0.6 ship now with nothing in it that a 0.4 app cannot open; the enforcing release re-enables it once the minimum is
0.5.

## What Changes

- A production build creates every new event **plain** again: its QR code and shared link are the fragment form every
  app version opens, so a 0.4 guest can join any event a 0.6 host creates.
- A rig build keeps creating encrypted events by default (its switch still reaches a plain one), so the encryption
  paths stay exercised by the integration suite and on devices.
- Everything an encrypted event needs stays in the app and on the site: joining, uploading to and receiving from one,
  the incomplete-invite wall, the lost-key line and its restore by the invite, the key in the Play install referrer,
  the event page opening one. Only *creation* changes.
- The Privacy Policy stops saying that events created with 0.6 or later are stored encrypted; the encryption
  paragraph returns with the release that creates encrypted events.
- `PENDING_CLEANUPS.md`: the `MIN_APP_VERSION` 0.5 bump is no longer due "before the 0.6 promote" but before the
  release that re-enables encryption; the "At the 0.6 promote" privacy-claim section moves to that release; a new
  entry records re-enabling encryption-by-default in production (code, spec, policy) once the minimum is 0.5.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `invite-link`: the events this version creates are plain, with the fragment-form invite; what an encrypted event
  promises (its key travels only in the invite, its photos open only with the whole invite) is unchanged but no
  longer stated as the default for new events.

## Impact

- `:adapter:generic:app` — `InertDevControls.createsPlainEvents` answers `true`, its test and KDoc.
- `:domain:ports` `DevControls` / `:domain:services` `EventKeyMinting` — KDoc only.
- `:test:contracts` `DevControlsContract` — the shipped clause expects a plain create; the switched clause moves to
  the rig's encrypted default so both cells stay covered.
- `:test:rig` / `:adapter:generic:mock` — no behaviour change (default stays encrypted); wording in
  `.claude/skills/rig-channel/SKILL.md`, `docs/architecture.md`, `CLAUDE.md`.
- `site/src/pages/index.astro` — the Privacy Policy paragraph.
- `PENDING_CLEANUPS.md`.
- No api change, no backend deploy; no data migration — encrypted events already created (rig/internal builds) keep
  working on every 0.5+ build.
