# Design

## Context

Every page the app opens outside itself goes through one command. `UiIntent.OpenLink` (from the menu, in
`Surfaces.onOpenLink`) and the update-required screen's store button (`StatusContainerHost.onOpenAppStore`) both fire
`commands.platform.openLink` with an `AppLinkRequest`. The entry point `OnOpenLinkTapped` calls
`SystemSurfaces.open`, which calls `SystemUi.openUrl` and logs a `Handoff.Refused` at `Error` as "nothing was handed
off" (`what=link`, `reason=…`). The command is fire-and-forget: nothing comes back to presentation. The `Handoff`
KDoc says so on purpose: "The app ACTS on neither answer". The menu's decision record
(`archive/2026-10-05-app-menu-bug-report`, D5) chose to log the refusal and nothing more.

On iOS the refusal is `UIApplication.openURL(_:options:completionHandler:)` completing with `false`
(`SystemUrlOpenerApi`), and iOS gives no reason. On Android, `AndroidSystemUi.start` already distinguishes "no app
claims it" from a `SecurityException`.

There is already a pattern for a command whose outcome the member is told: the bug report (same record, D7).
`OnSendDiagnosticsTapped` answers on a `RequestStatusSink<ReportOutcome>`. `ReportFlow.send` awaits the answer by
request id, sets `Overlays.reportNotice`, and clears it after `REPORT_NOTICE_MILLIS` or on tap. `StatusScreen` draws
it as an `AppNotice` over every layer, the update-required layer included.

Field evidence and the measurement that ruled out the app: proposal.md, Why.

## Goals / Non-Goals

**Goals:**
- One answer for every link: the menu's two pages and the store page are told the same way, through the one command
  they already share.
- The next field refusal on iOS says which of "the app was not in front" and "iOS says nothing may open this
  address" applies.

**Non-Goals:**
- No in-app browser. SFSafariViewController would change "opens in the browser" and is likely blocked by the same
  restriction.
- No copy-the-address button. Naming the address is enough to find the page, and a clipboard write is a second
  hand-off that can fail in turn.
- The share sheet's refusal, and Settings' refusal, stay as they are: different surfaces, no field reports.
- Lowering the refusal's log level. It stays a report until the data has named the cause.

## Decisions

### D1: The open-link command answers on a request status, as the report does

`AppLinkRequest` is wrapped in an `OpenLinkRequest(id, link)`, and `OnOpenLinkTapped` answers
`LinkOutcome { OPENED, NOT_OPENED }` on a `RequestStatusSink<LinkOutcome>`. `SystemSurfaces.open` returns the
`Handoff` it already receives, and the entry point maps it. Its `ensure("answer")` settles `NOT_OPENED`: a run that
failed before the hand-off opened nothing, and saying so is the honest default. If the browser did open, the member
is in the browser and the notice clears before they return.

*Alternative:* presentation reads the `Handoff` itself. Rejected: presentation fires commands and reads statuses; it
never calls a port.

*Alternative:* keep fire-and-forget and have `SystemSurfaces` push a "refused" event into a shared flow. Rejected: it
is a second way for a result to reach the screen, beside the request statuses every other answered command uses.

### D2: One brief-notice mechanism, two notices

Add `Overlays.linkNotice: LinkNotice?`. `LinkNotice` names what could not be opened: the site, carrying the address
to show (the site origin without its scheme, from the same generated `LINK_ORIGIN` `OwnPageText` builds from), or a
store, carrying the `Store` whose name is shown. The Privacy Policy shows the site's address, because the policy is
on that page.

The timer-and-dismiss mechanics in `ReportFlow` (`noticeClear`, `REPORT_NOTICE_MILLIS`, `dismissNotice`) move into
one small presentation helper that both notices use, each with its own overlay field and its own dismiss intent
(`LinkNoticeDismiss` beside `ReportNoticeDismiss`). Duplicating the timer would be two copies of one rule.
`StatusScreen` draws `linkNotice` with the existing `AppNotice`, in the same slot. If both are set at once, the newer
one wins the slot and the older one is cleared.

*Alternative:* one generic `notice: Notice?` overlay replacing `reportNotice`. Cleaner in the long run, but it
reshapes `UiState`'s serialized form, which the rig and its tests read. Not worth it for two notices; noted as an
option should a third arrive.

The words are UI, not spec: in `:ui:screens`' `strings.xml`, English and German. For example "Your phone didn't
open the page. It's at snapsync.stho.net" and "Your phone didn't open the App Store."

### D3: A refusal carries the circumstances the adapter could read

`Handoff.Refused` gains `circumstances`: a small platform-neutral value with three facts, each either read or
"could not read" (absence is never silent):

- **in front**: whether the app was the active app when the platform answered.
- **any app claims it**: whether the platform reports some app able to open the address.
- **scene**: the platform's own word for the window's state, as text, for the log only.

`SystemSurfaces.recorded` logs them as fields on the same "nothing was handed off" line, which stays at `Error` with
its event literal unchanged. Bugsink therefore keeps grouping it under SNAPSYNC-71 and now carries the facts.

On iOS, `UrlOpenerApi` gains one call, made only after a `false` and on the main queue like the open itself. It reads
`UIApplication.applicationState`, `canOpenURL(url)` and the connected window scene's `activationState`. The
recording and replaying implementations record it, so the `LinkOpener` device recording is re-recorded on the SE2.

On Android, "any app claims it" is already what `ActivityNotFoundException` means, and "in front" is
`foreground.current != null`. Both are filled from what `start` already knows; nothing new is asked of the OS.

*Alternative:* put the facts into `reason`'s string. Rejected: Bugsink fields are what the operator filters on, and a
string would hide them.

### D4: Measure what the facts mean before reading the field data

`canOpenURL` answers only for schemes the app declares in `LSApplicationQueriesSchemes`, and whether that applies to
`https` is to be measured, not assumed. The apply phase measures on the SE2:

1. `canOpenURL(https://…)` with and without `http`/`https` declared;
2. what `openURL` and the three facts answer with Screen Time limiting Safari (Content & Privacy Restrictions,
   Allowed Apps, Safari off).

Step 2 is also the direct test of the leading hypothesis. If it refuses, a `LinkOpener` clause pins it as a
recorded platform fact.

**Measured** (SE2, iOS 27.2.1, rig build of this change, 2026-11-18):
- With Screen Time's Allowed Apps → Safari off, tapping the menu's Privacy Policy is **refused**: `openURL` completes
  `false`, iOS logs `LSApplicationWorkspaceErrorDomain Code=115`, and the app logs exactly the field line of
  SNAPSYNC-71, now with `in_front=true claimed=false scene=foregroundActive`. The Screen Time hypothesis reproduces the
  field refusal. The notice shows on the screen the member is on ("Your phone didn't open the page. You'll find it at
  snapsync.stho.net.").
- Without `LSApplicationQueriesSchemes`, `canOpenURL(https://…)` answered `false` under that restriction, and iOS
  redacts its reason, so the restriction and an undeclared scheme cannot be told apart.
- With `http`/`https` declared, it still answers `false` under the restriction. So `in_front=true claimed=false` is
  the restriction's signature. The declaration stays: Apple documents that an undeclared scheme always answers
  `false`, which would make the fact say nothing. That half is documented, not measured — an unrestricted phone never
  refuses, so the app never asks.

### D5: No `Bugsink-Resolves` trailer

The change does not stop refusals; it tells the member and records why. SNAPSYNC-71 stays open as the issue that
collects the new fields. SNAPSYNC-51 and -53 carry the retired message and receive nothing from builds since 0.7; they
can be resolved by hand as superseded by SNAPSYNC-71, which is the operator's call.

## Risks / Trade-offs

- [Declaring `http`/`https` in `LSApplicationQueriesSchemes` draws App Review attention] → Declare only if D4's
  measurement shows `canOpenURL` needs it. Two well-known schemes are a common declaration.
- [`NOT_OPENED` shown for a run that crashed after the browser opened] → The member is in the browser; the notice
  clears after a few seconds, before they return.
- [The notice covers the update-required notice's bottom area] → It sits where the report's word already sits, and
  clears itself.
- [The `Handoff` KDoc and the menu record's D5 say the app acts on no answer] → That KDoc is rewritten here. This
  design supersedes D5 for links, and the archive keeps D5 as history.

## Migration Plan

App-only and additive. There is no stored state; rollback is a revert. The re-recorded `LinkOpener@IOS_DEVICE_APP.rec`
ships in the same PR as the adapter call it records.

## Open Questions

- Whether the facts confirm Screen Time, App Review devices or something else. Answered by the field events after
  release; the next step (resolving SNAPSYNC-71, lowering the log level) is a later change.
