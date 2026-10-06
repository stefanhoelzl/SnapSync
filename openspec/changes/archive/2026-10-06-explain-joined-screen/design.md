## Context

The joined layer (`JoinedLayer` in `:ui:screens`) renders a fixed stack: a QR hero (eyebrow + `AppQrCode`),
the status line with its counts, the limited-access buttons, and a bottom cluster of bare icons (settings ·
share · leave) passed to `ScreenLayout`'s `bottomActions` slot. Everything the new explanation needs is
already on `Layer.Joined`:

- `membership: EventConfig` — `direction` (share / receive), `saveToAlbum`, the shared range
  (`minPhotoDate` / `maxPhotoDate`), the event `name`, `closed`;
- `health: SyncHealth` — `NeedsAccess(permission)` is the no-access case;
- `canChoosePhotos` — true exactly under a limited grant;
- `inviteUrl` — what both invite routes carry.

The design was settled in an interview with a living mock (both kept outside the repo); this file records
the decisions and the alternatives they beat. Motivation: see proposal.md. Requirements: the three delta
specs.

## Goals / Non-Goals

**Goals:**
- The explanation is a pure function of the reduced state already on `Layer.Joined` — no new port, no new
  read, no persisted flag.
- The docked footer reuses `ScreenLayout`'s existing pinned `bottomActions` slot; the joined layer's content
  scrolls above it on the SE2 (375 × 667 pt), the smallest supported phone.
- Every new word is a string resource in `values` and `values-de`.

**Non-Goals:**
- Detecting that background delivery stalled. The "photos not arriving?" row is static advice; the app only
  sees a stall while it is open, which is exactly when it is not stalled.
- Changing the status line's priority, its arrows, or which states exist — only its words.
- Changing the join screen, the reconfigure screen, or the create screen.

## Decisions

**D1 — Order: heading → status → "How it works" → docked footer.** The status answers "is it working?"
first; the explanation sits under it; the invite actions, a divider and the text actions (Event settings,
Leave event) form one footer pinned at the bottom. *Alternatives:* invite first (the host's first job), or a
numbered 1-2-3 story with status last. Rejected: the screen is opened far more often to check on photos than
to invite, and pinning the invite keeps it one tap away anyway.

**D2 — Two equal filled buttons, "Share invite link" and "Show QR code"; no section title.** *Alternatives:*
filled Share + outlined QR (link as the primary path), side-by-side buttons. Equal weight was chosen by the
operator; side by side does not fit "Einladungslink teilen" at 375 pt. The footer carries no "Invite family &
friends" eyebrow, because that title would sit over Settings and Leave too; a thin divider separates the
invite pair from the text actions instead.

**D3 — The QR opens in a bottom sheet** (event name, large QR, a caption addressed to the member showing it:
"Let family and friends scan this with their camera"), with no share fallback inside it. *Alternatives:* a
modal dialog with a Done button; a full-screen page with raised brightness. The sheet matches the app's other
sheets (rename, report) and needs no extra button. It is screen-local navigation, so it is an `Overlays`
flag (`showingQr`) with open/dismiss intents, masked against a non-joined or closed layer exactly like
`renaming`.

**D4 — Settings and Leave are stacked, centred, borderless text buttons** ("Event settings" in the primary
colour, "Leave event" in the error colour). *Alternatives:* outlined buttons (competed with the green invite
pair), a grouped list, tonal + text, one row of links. On a closed event only "Leave event" remains, in the
same docked footer so the footer's top shadow stays.

**D5 — Each explanation row: title says what is happening, caption says how or why.** Rows and their sources:

| Row | Shown when | Title / caption (en) |
|---|---|---|
| Share | sharing on, full access | Your photos are shared / Photos you take ‹range› go to the group. |
| Share | sharing on, limited | Only selected photos are shared / Selected photos taken ‹range› go to the group. + Choose more photos · Allow full access (stacked) |
| Share | sharing off | You're not sharing / Turn on sharing in ‹Settings› to share photos you take ‹range›. |
| Share | no access | Nothing is shared / ‹Allow photo access› to share photos you take ‹range›. |
| Receive | receiving on, album | Theirs land in your gallery / In the album “‹name›”. |
| Receive | receiving on, no album | Theirs land in your gallery / Next to your own photos. |
| Receive | receiving off | You're not receiving / Turn on receiving in ‹Settings› to get the group's photos in your gallery. |
| Receive | no access | Nothing is received / ‹Allow photo access› to get the group's photos in your gallery. |
| Hint | always | Photos not arriving? / Open SnapSync and they catch up. |

‹range› is the member's own shared range (`minPhotoDate`..`maxPhotoDate`) through `DateFormats`, so one
sentence is true before, during and after the event and for a custom or "from now" range. *Alternatives:*
"Just take photos" (false after the end and outside a custom range) and per-phase sentences (more strings,
no more truth). The receive caption dropped "no need to open SnapSync", which read as contradicting the hint
right below it. A closed event shows only the receive row and the hint.

**D6 — Icons are plain Material glyphs:** camera (share) and photo library (receive) in the primary colour,
a touch glyph (hint) in the muted colour. Switched off = the same glyph slashed, muted; no access = the same
glyph slashed, amber. One glyph per direction, with colour telling a choice from a problem. *Alternatives:*
tinted discs, the status line's arrows, a lock for no access (rejected: two symbols for "not happening").

**D7 — The links reuse existing intents.** "Settings" → `OpenReconfigure` (the event's settings surface);
"Allow photo access" → the status line's own branch (`RequestPermission` when never asked, `OpenSettings`
otherwise), so the link and the line can never do different things. The links are real controls in the
accessibility tree (not just styled text), each with its own click target.

**D8 — Status line words** (meaning and priority unchanged):

| State | Before | After (en) | After (de) |
|---|---|---|---|
| neutral, still reading | Syncing… | Loading… | Wird geladen … |
| nothing left | In sync | Up to date | Alles aktuell |
| transfer running | Synchronization ongoing… | Photos arriving… | Fotos kommen an … |
| work left, idle | Synchronization pending… | Photos queued… | Fotos warten … |

"Photos arriving…" is used for both directions: from the member's side, their photos *arrive* for the
group as much as the group's arrive for them (glossary: photos arrive / land).

**D9 — German.** du-form, "Event", "Galerie", matching `values-de`. The joined statement becomes "Du nimmst
an diesem Event teil". Drafted copy: the explanation's eyebrow HOW IT WORKS / SO FUNKTIONIERT'S ·
Einladungslink teilen ·
QR-Code zeigen · Event-Einstellungen · Event verlassen · Deine Fotos werden geteilt · Nur ausgewählte Fotos
werden geteilt · Fotos der Gruppe landen in deiner Galerie ("Ihre landen…" was avoided: it reads as the formal
"your") · Fotos kommen nicht an? / Öffne SnapSync, dann holen sie auf. · Du teilst nicht · Du empfängst nicht
· Es wird nichts geteilt · Es wird nichts empfangen · „‹name›“ beitreten · Lass Familie und Freunde das mit
ihrer Kamera scannen.

**D10 — Spec scenario titles that quote "In sync" keep their old titles in the deltas.** `openspec validate`
treats a renamed scenario inside a MODIFIED block as a dropped one. Their bodies are updated; the titles are
retitled in the main specs by hand after archiving (a task).

## Risks / Trade-offs

- [The SE2 cannot fit the German no-access or switched-off screen without scrolling] → the footer is pinned
  and the content scrolls under it; the shot scenarios cover the in-sync state, and a UI test asserts the
  footer stays on screen with the content scrolled.
- [The hint's promise "open SnapSync and they catch up" overpromises when the cause is offline or a full
  event] → the status line names those causes with higher priority; the hint stays true for the common case
  (the OS deferring background work), which `background-upload` and `receiving-photos` already guarantee to
  catch up on open.
- [Album name on Android may carry a number suffix (`DCIM/SnapSync/<name> 2`)] → the row names the event's
  name, which is what the member recognises; see Open Questions.
- [Marketing screenshots change] → refresh them in this change (`in_sync`, and `joining` is unaffected).

## Migration Plan

Pure UI and copy; no data migration. Ships behind nothing. Rollback is a revert.

## Open Questions

- Whether Android's numbered album folder should be named in the receive row's caption, or the event name
  is enough. Answerable at implementation from `AlbumMapService`; does not change the specs.
