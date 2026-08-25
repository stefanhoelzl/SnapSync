## Purpose

How the app behaves under a **partial** photo-library grant (iOS `.limited` → `PermissionStatus.LIMITED`):
the user's hand-picked selection **is** the membership's own-photo scope. That reframe is what makes a
partial grant a first-class working state rather than a failure mode — "is everything shared?" is
answerable again, because the selection defines "everything", and "In sync" over the chosen set is true.
For a guest at a stranger's event, picking exactly what to share is the *more* natural grant.

Three measured platform facts shape every requirement here (SE2; the probe records live with the
decision records). First, iOS's automatic limited-access alert is armed by the **library changing**, not
by reading: a `PHAsset` fetch under `.limited` surfaces it **iff the library gained content outside the
app's selection since the app last looked**, armed **once per change** rather than once per fetch — and
app-created assets join the selection at creation, so an import, and any fetch resolving what it created,
never arm it. Read volume therefore does **not** change the alert count, and the read discipline below is
kept for a different reason: under a partial grant the selection *is* the scope, so reading it rather
than walking the library is simply the correct source. The residual is real and unavoidable — **every
photo the member takes costs one system prompt**, surfaced by the app's next read, which no read strategy
avoids. Second, a partially-granted process **cannot change its upload-job registration at all** —
`setUploadJobExtensionEnabled` is refused in both directions with `PHPhotosErrorAccessUserDenied`
(3311) — so the ≥26.1 PhotoKit background-upload extension is never registered from `.limited` and the OS
never invokes it there; hence uploads run the app-driven mechanism. Third, asset and album **creation**
are unrestricted under `.limited`; hence downloads and the event album need no special handling at all,
and receive-only is a valid resting state.

Decision record: `changes/archive/2026-07-20-accept-limited-photo-access` (`PROBE-FINDINGS.md` +
`LIMITED-ACCESS-DESIGN.md`) established this capability;
`changes/archive/2026-08-06-correct-limited-access-read-premise`
(`PROBE-FINDINGS.md`, SE2 / iOS 26.5.2) **supersedes its fact 5** — the alert rule above replaces the
"every autonomous fetch storms" claim, which explained the same observations less well. Evidence for the
new rule: one device, one OS point release, n = 1 out-of-scope change; re-measure at the next iOS major.
`changes/archive/2026-08-25-collapse-upload-tier-seam` (D11, D11b; SE2 / iOS 26.6) **corrects fact 2** —
the earlier reading, that registration *succeeds and lies* under `.limited`, is contradicted by
measurement: both directions are refused, and the enable was reached only through a development
mechanism override. Evidence: one device, one OS point release; re-measure at the iOS 27 GM
re-assessment.

## MODIFIED Requirements

### Requirement: A limited grant resolves the app-driven mechanism by resolution, not by a branch

Under a partial grant the app-driven mechanism SHALL be the one **resolution** yields on every OS
version (`upload-lifecycle`, "The upload mechanism is resolved, never selected"), and on an OS carrying
the OS-driven mechanism that resolved producer SHALL **attempt** to relinquish the OS-driven registration
before it pumps.

The attempt SHALL be understood as an attempt, not an accomplished teardown. Under a partial grant the
platform refuses it with `PHPhotosErrorAccessUserDenied` (`ios-photokit-upload`, "The registration cannot
be changed under a partial grant"), so any record that already exists survives. That is safe rather than
merely tolerable: the OS does not invoke the extension under a partial grant, so a surviving record
produces no second ledger writer, and a return to a full grant re-registers through the disable→enable
ritual regardless. Where the same resolution cell is entered under a **full** grant — which is where a
development mechanism override places the app-driven mechanism — the relinquish succeeds and is
load-bearing.

Deregistration under a partial grant is therefore not a separate rule from the forced-tier case: both are
the same resolution cell. A limited member on such an OS previously depended on a lifecycle transition
firing to tear the registration down; making it a property of the resolved mechanism removes that
dependence, whether or not the platform honours the attempt.

#### Scenario: A downgrade to a limited grant relinquishes via the resolved mechanism

- **WHEN** photo access transitions from `GRANTED` to `LIMITED` on an OS carrying the OS-driven mechanism
- **THEN** resolution yields the app-driven kind, whose producer attempts to deregister the extension
  before pumping, and no separate deregistration rule is consulted

#### Scenario: A refused relinquish does not block the pump

- **WHEN** that relinquish attempt is refused because the grant is partial
- **THEN** the surviving registration is left in place, the app-driven mechanism pumps regardless, and
  exactly one process writes ledger records
