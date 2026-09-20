## Why

A user standing on the joined event screen asked *"what do I need to do here?"*. The invite QR's
caption reads **"Scan to join this event"** — a second-person imperative directly beneath a scannable
code — so the member holding the phone takes it as their own task, although they are already joined.

The caption was written for a second audience: the code comment at its call site justifies it as
instructing *"the person scanning it"*. That reader does not exist in practice — a scanner looks
through their own camera, not at 14sp type on someone else's screen. The only person who reliably
reads the caption is the member, and to them it is false.

This is not a missing label. The accent eyebrow `SHARE THIS EVENT` sits directly above the card and
has shipped since `v0.1` (2026-07-21); the confused user was on a build that had it. The member-facing
line exists and was not read — the caption is what gets read, and the caption is what lies.

## What Changes

- The joined layer's QR caption becomes **"Let someone else scan this to join"** — addressed to the
  member, with the scanning assigned to a person the reader cannot be.
  - Not *"guests"*: the confused user **was** a guest, and host and guest see the identical screen, so
    any role word the reader can apply to themselves reproduces the bug in a new form.
  - Measured in the forge harness at 390pt: the caption still fits on **one** line, and the QR card
    grows slightly **wider** (to ~260pt) rather than taller — the card sizes to its content. No
    vertical rhythm changes.
- The stale two-audience rationale in the comment above the call site is rewritten with the string.
- `event-invite-qr` and `desktop-test-harness` **stop quoting the literal** and require the caption's
  *intent* instead: a caption telling the member that someone else scans this code to join. The exact
  words live in `JoinedLayer.kt` alone, mechanically pinned by the existing `StatusScreenTest`
  assertions, so a later copy tweak needs no spec edit.
- The committed screenshot raws are re-captured, because `in_sync-{light,dark}` render this card and
  both the App Store listing and `site/` derive from those raws.

Deliberately **out of scope**, each with its reason:

- The create screen's hint *"Or scan a QR code in the Camera app to join one."* stays. There the
  imperative is correct — that reader has no event and really must go scan one, and the line even
  names where.
- The bottom cluster's icon-only share button gets no visible label. Its three flat icon-only peers
  are a design-system idiom; labelling one changes the cluster, the idiom and the harness spec. If the
  invisible share path (the only way to invite someone who is not in the room) turns out to matter, it
  is its own change.

No **BREAKING** changes: nothing but display copy and the requirements that describe it.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `event-invite-qr`: the caption requirement stops pinning the literal string and instead requires a
  member-addressed caption stating that someone else scans the code to join.
- `desktop-test-harness`: the harness's invite-affordance requirement likewise stops quoting the
  caption literal, requiring only that the join QR renders with its caption.

## Impact

- `ui/screens/src/commonMain/kotlin/app/snapsync/ui/JoinedLayer.kt` — the one string (line 58) and the
  comment above it. `AppQrCode` has exactly one call site, so nothing else renders this caption.
- `ui/screens/src/commonTest/kotlin/app/snapsync/ui/StatusScreenTest.kt` — three assertions on the
  literal (one absence, two presence). They remain the mechanical pin; only the string changes.
- `openspec/specs/event-invite-qr/spec.md` (purpose line, one requirement, one scenario) and
  `openspec/specs/desktop-test-harness/spec.md` (two places).
- `screenshots/in_sync-light.png`, `screenshots/in_sync-dark.png` — re-captured via
  `screenshots.yml` and committed. `joining` is the join gate (no QR) and `create` has none, but the
  workflow emits all six raws and `create` legitimately re-diffs in its 90×32px clock region.
- Downstream of those raws, with no separate action: the App Store listing (at the next release) and
  the `site/` landing page (on merge).
- No domain, adapter, backend or build change; no new dependency.
