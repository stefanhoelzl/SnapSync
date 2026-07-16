## Context

A shipped attention state with no owner:

```
  CODE                                        SPECS
  ────                                        ─────
  StatusContainerHost.kt:546                  sync-status-screen  "three rungs"
    !attested -> SyncHealth.Unattested                            "the SOLE attention state"
  UiState.kt:154  data object Unattested      design-system       "NeedsAccess … the ONLY variant
  AppStatusLine.kt:172  Surface(AmberContainer)                    carrying a background"
    -> AppSyncStatus.CannotVerifyDevice       device-attestation  "reduced into the EXISTING
                                                                   visible error state"   ← false
                                              desktop-test-harness cites device-attestation as owner
```

Every one of those spec claims is wrong, and the rationale for the code is written down — in
`tasks.md` 4.5, `design.md` D10b, and two KDocs. Only the contract is missing.

## Goals / Non-Goals

**Goals**
- Give the state one owner, chosen so the precedence lives in one place.
- Lift the existing rationale rather than re-derive it; it is better than anything I would write fresh.
- Unblock the two sweep corrections that were skipped for want of an owner.

**Non-Goals**
- **Changing behaviour.** No code moves.
- **Removing the state.** See D3.
- **`design-system`'s reduced-motion requirement.** Unimplemented, but the spec is right and the code is
  wrong — a code defect, and a different argument.
- **Fixing D11 in the archive.** The archive is immutable history. D11 was falsified during implementation
  and `tasks.md` 4.5 records that; rewriting it would destroy the evidence of how this happened.

## Decisions

### D1. `sync-status-screen` owns it, because the precedence must live in one place

The pull toward `device-attestation` is real — the state *is* attestation's consequence, and
`desktop-test-harness` already (wrongly) cites it. Rejected, for two reasons.

**It is a precedence rung before it is an attestation fact.** Its whole contract is *where it sits*: below
`NeedsAccess`, below `NotStarted`, above sync progress. Three of those four rungs are specified in
`sync-status-screen`. Putting the fourth elsewhere splits one ordered list across two capabilities, and the
next person to add a rung has to find both.

**`device-attestation` is backend-only.** It never mentions `SyncHealth`, the status line, or presentation.
Giving it a UI surface it otherwise has nothing to say about is how a capability starts describing things
its reader did not come for.

The precedent settles it: **`NotStarted` is the structurally identical case** — a health value derived from
membership rather than from the sync snapshot, ranked between two others — and it was added to
`sync-status-screen` **69 minutes before** `Unattested` shipped (`026c42a` at 21:38, `1f85ce6` at 22:47 on
2026-07-14). The same change that had just demonstrated where this kind of thing goes put the next one
nowhere.

### D2. "The sole attention state" becomes "the sole **actionable** attention state"

The tempting fix is to delete the "sole" clause. That would lose something true.

`NeedsAccess` and `Unattested` are not peers. `NeedsAccess` is **tappable** — it opens Settings, because the
user can fix it. `Unattested` is deliberately **not tappable and carries no chevron**, because there is
nothing the user can do: the app renews on the next wake, and *opening the app is a wake*. The code says so
where it renders (`AppStatusLine.kt:94`, "`NeedsAccess` and `CannotVerifyDevice` carry a background";
:172-191, "the same attention treatment as NeedsAccess — but NOT tappable, and with no chevron: there is no
action the user can take").

So the sentence keeps its force and gains precision: `NeedsAccess` is the sole state that asks the user for
something. Both carry a background, because both are attention; only one is a request.

### D3. Why not delete the state and keep the three-rung spec

It would make four specs true by touching one file, and it would reintroduce an invisible failure.
`tasks.md` 4.5 is explicit:

> The BACKGROUND stall needed a new state — without it a device whose token died reports "Syncing…" forever
> while every upload `401`s.

The interactive paths (create, join) were already covered — a gated `401` flows into `UiState.CreateEvent(error)`
and `JoinPhase.LoadFailed`. The *background* stall had no surface at all, and it is the one the user cannot
discover: uploads silently stop while the screen says they are proceeding. That is the shape this project
treats as unacceptable — "an event photo that silently fails to upload is invisible and unfixable".

The state's self-clearing property is what makes it honest rather than noisy: opening the app **is** a wake,
so looking at the screen renews the token and clears the line. It survives to be seen only when renewal
itself keeps failing — offline, or the backend refusing us — which is exactly when there is something to say.

### D4. `device-attestation` keeps a pointer, and its false requirement is corrected rather than deleted

The requirement at :309 — "the failure SHALL be reduced into the **existing** visible error state" — is not
merely stale, it was **never true**: it is D11's promise, written before implementation contradicted it.

It is corrected rather than dropped because it still owns a real obligation: *the stall must be visible at
all, and must never be silent*. That belongs to attestation — it is attestation's failure. What does not
belong to it is *which* state renders it. So the requirement keeps its "never silent" force and
cross-references `sync-status-screen` for the surface, which is the shape a backend capability should have
toward a UI concern.

Its placeholder citation was already resolved to `changes/archive/2026-07-14-add-device-attestation` in the
drift sweep, so a reader who wants the reasoning can now reach `tasks.md` 4.5 — which is where it has been
the whole time.
