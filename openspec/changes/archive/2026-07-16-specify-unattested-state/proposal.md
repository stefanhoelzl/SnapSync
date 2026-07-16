## Why

`SyncHealth.Unattested` is a shipped attention state on the status screen. **No spec owns it.**

It is a live fourth rung in the health precedence (`StatusContainerHost.kt:546`), a real sealed variant
(`UiState.kt:154`), and it renders as `AppSyncStatus.CannotVerifyDevice` — an amber-backgrounded status
line (`AppStatusLine.kt:172-191`). Four specs are wrong about it, in three different ways:

- **`sync-status-screen`** states a **three**-rung precedence and calls `NeedsAccess` "**the sole attention
  state**". There are four rungs and two attention states. `grep -n "attest" sync-status-screen/spec.md`
  → zero hits.
- **`design-system`** says the status line renders `NeedsAccess` as "the **only** variant carrying a
  background". `CannotVerifyDevice` carries one too — `AppStatusLine`'s own KDoc says so out loud
  ("`NeedsAccess` and `CannotVerifyDevice` carry a background"). The variant is absent from the spec's
  sealed-value list entirely.
- **`device-attestation`** is worse than silent: it is **false**. "The failure SHALL be reduced into the
  **existing** visible error state (a sealed domain error → `UiState`)" — it is not the existing state; it
  is a new `SyncHealth` variant *and* a new `AppSyncStatus`.
- **`desktop-test-harness`** is the only spec that names `Unattested`, and it cites capability
  `device-attestation` as its owner — a forward reference to a spec that never claimed it.

**Nothing was lost; the delta was never written.** `git log -S "Unattested"` over `:domain:presentation` +
`:domain:ui` returns exactly one commit, `1f85ce6`, which created the change directory *and* the
implementation in one squash. Its `specs/` delta covered nine capabilities — every one backend — while the
same commit edited `UiState.kt`, `StatusContainerHost.kt`, `AttestedSource.kt` and `AppStatusLine.kt`. The
archive run synced faithfully what it was given.

The cause is structural, not careless. That change's **D11** promised *"Attestation and `401` failures
become a sealed domain error reduced into the **existing** visible error state — **no new screen, no new
`App*` component**"*, so `sync-status-screen` and `design-system` were never on its Modified-Capabilities
list. Implementation then discovered D11 was wrong — and recorded the correction in **`tasks.md` 4.5**, an
implementation log rather than a contract:

> The BACKGROUND stall needed a new state — without it a device whose token died reports "Syncing…" forever
> while every upload `401`s. Added `SyncHealth.Unattested` → `AppSyncStatus.CannotVerifyDevice` (the
> NeedsAccess attention treatment, but **not tappable** — there is no action the user can take). Raised
> ONLY when there is no usable token AND obtaining one failed — never for a merely stale token, which the
> next wake renews. Ranked below `NeedsAccess` … and above sync progress ("Syncing" would be a lie).

D11 itself was never amended. Its neighbour D10b even *uses* the new state while D11 still denies it exists.

So the reasoning is excellent and complete — it is simply filed where no reader of the contract will look.
This change moves it into the contract. **Removing the state is not the alternative**: without it, a device
whose token died reports "Syncing…" forever while every upload `401`s — invisible, and precisely the
failure it was added to surface.

## What Changes

- **`sync-status-screen` takes the rung and the variant.** It already owns the `SyncHealth` family and the
  precedence, and `NotStarted` — the structurally identical case — was added there **69 minutes before**
  `Unattested` shipped. The precedence becomes four rungs; "the sole attention state" becomes the sole
  **actionable** attention state, which is the distinction the code's own comments already turn on.
- **`design-system` takes `AppSyncStatus.CannotVerifyDevice`.** Added to the sealed-value list; "the only
  variant carrying a background" amended to cover both attention variants, with the not-tappable /
  no-chevron distinction that separates them.
- **`device-attestation` keeps only a pointer.** Its false "existing visible error state" requirement is
  corrected to cross-reference `sync-status-screen`, which is the shape a backend-only capability should
  have toward a UI state.
- **`desktop-test-harness` re-points** its citation from `device-attestation` to `sync-status-screen`.
- **No behaviour changes.** No code moves. The text lifts near-verbatim from `tasks.md` 4.5, the `UiState`
  KDoc, and `StatusContainerHost`'s comments — all of which already argue it well.

## Impact

- **Affected capabilities**: `sync-status-screen` (gains a rung + a variant), `design-system` (gains a
  component + an amended requirement), `device-attestation` (a false requirement corrected),
  `desktop-test-harness` (a citation re-pointed).
- **Affected code**: none.
- **This unblocks two corrections the drift sweep had to leave lying.** `design-system`'s "only variant
  with a background" and `sync-status-screen`'s three rungs were both identified as false and both skipped,
  because fixing the sentence without giving the state an owner would only relocate the lie.
- **What this does not fix**: `design-system`'s "SHALL respect reduced-motion preferences" is unimplemented
  (`AppStatusLine.kt:210` pulses unconditionally; zero hits repo-wide). That requirement is *right* and the
  code is wrong — a code defect, not this change's business.
