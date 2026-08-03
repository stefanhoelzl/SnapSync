## Why

An event's name is fixed at creation and can never be changed. A host who mistypes it — or names an
event before it takes on its real shape ("Weekend" that became "Ana's 30th") — has exactly one remedy:
delete nothing, because there is no delete, and instead live with the wrong name on every member's
status screen, in every member's event album, and on the web download page's zip filename, for the
event's whole 30-day lifetime.

The name is also the one marker field whose immutability buys nothing. The write-once rule on the event
marker (`event-creation`) names its threats precisely: a mutation route would let anyone holding the
event id "retroactively widen every future joiner's default scope — or extend an event's own limits".
A name does neither. It is cosmetic to the extension, cosmetic to the upload gate, and load-bearing
for nothing but display.

## What Changes

- A joined member renames the event from the status screen: a pen beside the event heading opens a
  text-prompt dialog pre-filled with the current name; confirming writes the new name to the backend
  and every member picks it up on their next foreground refresh.
- The backend gains `PATCH /events/:id` accepting `{ name }` only — the **first and only** mutation of
  a marker that is otherwise write-once. Same device-token gate as `POST /events`, same name validation.
- Any joined member may rename. There is no owner concept and none is introduced: possession of the
  event id already grants uploading into the event and downloading every photo in it, so renaming is
  strictly weaker than what a holder already has.
- A rename **never** tears a membership down. A `404` from the rename route is one witness; the
  self-leave (`leave-event`) requires two, one of them offline, and that stays `MembershipRefresh`'s
  path alone.
- `ScreenLayout` gains an optional `onEditHeading` callback, mirroring the existing optional
  `onTitleDoubleTap`. `AppBugReportSheet` generalizes into a reusable text-prompt sheet serving both
  the diagnostic dump and the rename.
- **Not changed:** event albums already created on members' devices keep their old title (the album is
  tracked by `localIdentifier`, not by name, so nothing breaks — the titles simply diverge); no push
  fan-out is added; the invite QR is unaffected (it carries the event id only).

## Capabilities

### New Capabilities
- `event-rename`: the device-side rename — the `EventRename` port and its outcomes, the
  `RenameEvent` use-case as a writer of the membership config, the `RenameStatus` seam, and the
  status-screen affordance and its dialog.

### Modified Capabilities
- `event-creation`: adds the `PATCH /events/:id` route and amends the *Event marker registry*
  requirement's write-once rule from "no marker field may change" to "no marker field except `name`
  may change, and only via the dedicated rename route".
- `sync-status-screen`: the joined layer gains a rename affordance on the event heading, alongside the
  existing enumeration of the settings / share / leave affordances.
- `design-system`: `ScreenLayout` gains an optional `onEditHeading`; `AppBugReportSheet` becomes a
  general text-prompt sheet carrying an initial value, an optional inline error, and a busy state.
- `harness-world-model`: the `:test:world` mini-edge answers `PATCH /events/<id>` — the route the
  integration tests exercise the shipped client against.
- `full-stack-harness`: the left pane's rename affordance drives the real rename command, mirroring the
  bug-report affordance's rule.

**Considered and needing no delta** (recorded for the archive's delta-completeness gate):

- `diagnostic-logging` — it specifies *"a bug-report sheet"* behaviorally and never names the component,
  so generalizing `AppBugReportSheet` changes none of its requirements. Its rules all survive: with an
  empty initial value, the generalized "confirm disabled while the trimmed value equals the initial
  value" collapses to the existing "confirm disabled while trimmed-empty".
- `reconfigure-membership` — its read-only event-name header and its network-free, cannot-fail Save are
  deliberately untouched (see design D8).
- `event-album`, `join-event`, `leave-event`, `event-link`, `photo-selection-policy` — behavior-preserving
  here by construction; the rename touches no album title, no join path, no teardown, no link payload,
  and no capture-date bound.
- `device-attestation` — the gate's behavior is unchanged. `attest.test.ts` adds the rename to the
  existing `GATED` table, which *asserts* the current middleware rather than altering it: the route is
  gated because it is a non-`GET` on `/events/<id>`, which the `publicRead` method check already excludes.
- `module-architecture` / `architecture-guards` — no law and no guard changed; the new port, feature, and
  command conform to the existing zone rules.
- `ios-app-shell` — `:app:ios` is wiring only: two parameters forwarded, no conditional, no new launch
  directive.
- `desktop-test-harness` — the shared `StatusPane`'s new rename parameters default inert, so the forge
  harness is behaviorally unchanged.
- `architecture-diagrams` — `architecture/` is regenerated output, not a contract change.

## Impact

- **`api/`** — `app.ts` (the new route, reusing `validateEventName` and `gateEvent`), `test/app.test.ts`.
- **`:domain`** — `ports/` (`EventRename`, `RenameOutcome`), `feature/membership/` (`RenameEvent`,
  `RenameStatus`, `RenameFailureReason`), `model/UserCommands.kt`, `compose/SnapSyncApp.kt`.
- **`:adapter:generic:app`** — `HttpEventRename`, a near-clone of `HttpEventCreation`.
- **`:ui:components`** — `ScreenLayout`, `AppBugReportSheet` → text-prompt sheet.
- **`:ui:presentation`** — `StatusContainerHost` exposes `renameStatus` as a screen-level param, not a
  `UiState` family.
- **`:ui:screens`** — `StatusScreen` wires the pen, the dialog, and the failure banner.
- **`:test:world`** — the mini-edge gains the route; **`:test:integration`** gains one end-to-end test.
- **No change** to the upload extension, the ledger, enrollment, device identity, the invite link, or
  the selection policy.
