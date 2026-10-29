# Proposal

## Why

Claiming the app specs with integration tests found six scenarios that describe outcomes the app does not
produce, and the app is right in each case. A member who returns to a finished event is not sent to the create
screen, because the app never leaves an event on its own (capability `event-lifetime`). Switching sharing off
withdraws the upload under way too, as capability `photo-sharing` already says, and no status line or count
shows work for it. The contract of record has to say what a member sees before these scenarios can be claimed.

## What Changes

- `app-experience`:
  - "The app keeps its place across backgrounding" loses its stale exception: the membership no longer
    "ends on its own".
  - "Returning after the event finished" now says what a member sees after a finished event. The joined screen
    is still there, and opening another event's invite shows the new event's join screen without asking to
    switch, as capability `invite-link` decides. Nothing joins on its own.
- `manage-membership`, "Settings changes take effect immediately":
  - Turning sharing off withdraws an upload already under way, the same as any other photo not yet shared, and
    starts no new upload. The words "let uploads already under way finish and count as shared" are removed.
  - The scenario "Turning sharing off lets uploads finish" becomes "Turning sharing off withdraws the upload
    under way".
- `sync-status`:
  - A direction the member switched off has no work and no arrow. The clauses that promised to show or count
    work "if the app ever does work in a switched-off direction" are removed from "Direction arrows show
    remaining work and live transfer" and "The joined screen counts what was shared and received".
  - Three scenarios are rewritten to the outcome a member sees:
    - "Work left in switched-off directions is still shown";
    - "Work in a switched-off direction is not masked";
    - "Work in a switched-off direction is counted".

- OpenSpec cannot rename one scenario, and these four scenarios need new names because their old names say the
  opposite. Each requirement holding one is therefore replaced whole, under a new name:

  | Old requirement | New requirement |
  |---|---|
  | manage-membership "Settings changes take effect immediately" | "A settings change takes effect as soon as it applies" |
  | sync-status "One status line in a fixed priority" | "The joined screen shows one status line in a fixed priority" |
  | sync-status "Direction arrows show remaining work and live transfer" | "Direction arrows show the work left and the transfer running" |
  | sync-status "The joined screen counts what was shared and received" | "The joined screen counts the photos shared and received" |

  Their other scenarios carry over word for word. Two sync-status requirements that cite the status line by
  name follow the new name:
  - "A device that cannot be verified is shown, and never blamed on the member";
  - "A failing service is shown while photos wait".

No app behaviour changes. The app already does everything the rewritten scenarios say. This change corrects the
contract and lets the scenarios be claimed.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `app-experience`: "The app keeps its place across backgrounding" drops the case where the membership ends on
  its own, and its scenario about a finished event is rewritten.
- `manage-membership`: "Settings changes take effect immediately" is replaced by "A settings change takes effect
  as soon as it applies". Turning sharing off withdraws the upload under way instead of letting it finish and
  count as shared.
- `sync-status`:
  - "One status line in a fixed priority" is replaced by "The joined screen shows one status line in a fixed
    priority", with one scenario rewritten.
  - "Direction arrows show remaining work and live transfer" is replaced by "Direction arrows show the work
    left and the transfer running". The clause about work in a switched-off direction is removed, and one
    scenario is rewritten.
  - "The joined screen counts what was shared and received" is replaced by "The joined screen counts the photos
    shared and received", with the same clause removed and one scenario rewritten.
  - "A device that cannot be verified is shown, and never blamed on the member" and "A failing service is shown
    while photos wait" only cite the status line's new name.

## Impact

- **Specs:** the three capabilities above. `photo-sharing`, `invite-link` and `event-lifetime` already say what
  the corrected scenarios point to and are unchanged.
- **Code:** no production change. One schema comment in the download store cites the counts requirement by
  name and follows the new name.
- **Existing claims:** 17 `@Verifies` claims in `:test:integration` name a replaced requirement. They are
  re-pointed to its new name.
- **Tests:** the corrected scenarios are claimed in `:test:integration` later in phase 22, by tests that drive
  the app as it is today.
- **Known gap, not addressed here:** after a withdrawal, the operating system's transfer of a withdrawn photo
  can still deliver its bytes to the service. The photo is not listed, so no member receives it. Cancelling
  that transfer is recorded as a follow-up.
