## Why

Four specs disagree about one line of code, and the three that lose are the ones that read first.

`upload-lifecycle` says `stop()` "SHALL NOT clear the ledger, SHALL **NOT clear the discovery cursor**, and
SHALL NOT delete stored bytes", with four scenarios pinning the cursor. `event-rejoin-reconciliation` says
"the ledger and discovery cursor are **never** cleared by that transition". `sync-ledger` echoes it.

`ios-photokit-upload` requires the opposite, and says why:

> Whenever the app disables the extension it SHALL, immediately after the disable, **both** (a) call the
> ledger's `clearRequested()` … and (b) **reset the discovery cursor** … Both are required: `clearRequested()`
> only makes the keys *absent*, but a settled cursor scans incrementally and would never re-surface them —
> so without the cursor reset the cleared photos are re-discovered only when the library next changes. This
> SHALL apply to **both** disable paths: the disable half of the `disable→enable` re-register, and **the leave
> use-case's extension-disable**.

`PhotoKitUploadProducer.stop()` does exactly that, and `stop()` *is* the leave path
(`UploadArm.onLeave()` → `producer.stop()`).

**This is not a collision between two positions. It is one position, written twice, and generalized wrong
once.** Both requirements landed in the same commit, `40a6ee2` — `upload-lifecycle/spec.md` and
`PhotoKitUploadProducer.kt` were created by it — and its own design mandates the clear:

> | **PhotoKit (≥26.1)** | the 3202 disable→enable toggle | `enable(false)` + `clearRequested` + **clear cursor** *(the OS disable wipes in-flight jobs; **this is the repair**)* |

Its D2 then wrote the prohibition while reasoning **only about the ledger** — "Leave keeps the ledger… the
PhotoKit tier already honors it. Only the app-driven tier wipes." True of the ledger. False of the cursor,
by D1's own table eleven lines above. The prohibition existed to kill `UrlSessionUploadController.leave()`'s
ledger-and-cursor wipe — a real bug — and swept the PhotoKit repair in without noticing.

The code carries the contradiction openly: `UploadArm.kt:11` tabulates PhotoKit `stop()` as "`enable(false)`
+ `clearRequested` + **clear the cursor**", and `UploadArm.kt:22` says "`stop()` **never clears the ledger or
the cursor**".

**The prohibition is defending something real, and the cursor is not it.** What must never be destroyed is
**dedup state** — the ledger's `COMPLETED` rows and the stored bytes — because destroying it re-uploads a
member's whole library into a stranger's event. The cursor is not dedup state. Clearing it costs one full
re-enumeration and nothing else:

- `COMPLETED` rows are untouched by `stop()` (it clears only `REQUESTED`), so stored photos never re-upload.
- Rejoin clears the cursor anyway: leave clears the marker, so any later provision mismatches, and
  `event-rejoin-reconciliation` **requires** the tier "clear the discovery cursor to force a full
  re-enumeration". The `stop()` clear is redundant on that path, not destructive.
- Measured, in that change's own task 6.4: `cleared cursor` → `discoverResources = 52 resource(s)` →
  `enumeration: 50 seen, **0 new**, 50 already-uploaded` → **0 `createJob`**, backend **54 → 54 unchanged**.

So the specs are wrong and the code is right — but only because the cursor is cheap to rebuild. That is the
distinction the prohibition should have drawn, and the one it now draws.

## What Changes

- **`upload-lifecycle`**: `stop()` SHALL NOT destroy **dedup state** — ledger rows and stored bytes. A tier's
  `stop()` MAY clear its **discovery cursor**, and only as a repair for damage its own mechanism causes, and
  only where `COMPLETED` rows survive so nothing re-uploads. The four cursor scenarios are re-pinned to the
  ledger and bytes; a new scenario states the carve-out and its condition.
- **`event-rejoin-reconciliation`**: "the ledger and discovery cursor are never cleared" → the **ledger** is
  never cleared. The cursor clause was collateral from the same generalization; every ledger clause stands.
- **`sync-ledger`**: the same cursor clause, same treatment.
- **`ios-photokit-upload`**: unchanged. It was right all along.
- **`UploadArm`'s KDoc**: it contradicts itself in one comment (`:11` vs `:22`) and its `onLeave` claims the
  cursor is "kept". Both are false on the PhotoKit tier today.

## Impact

- **Affected capabilities**: `upload-lifecycle`, `event-rejoin-reconciliation`, `sync-ledger` (each loses a
  cursor clause, keeps every ledger clause).
- **Affected code**: comments only — `UploadArm.kt`'s KDoc. **No behaviour changes**: the code already does
  what the amended contract permits.
- **The risk this accepts.** A prohibition with an exception is weaker than one without. The exception is
  bounded by a *condition* rather than by a tier name — "only where dedup state survives" — so a future tier
  cannot invoke it to clear a cursor that *is* load-bearing. But it is prose, and prose can be
  misread; an absolute rule cannot. Accepted because the alternative is a rule the shipped code violates,
  which teaches readers the rules are approximate — a worse failure than a rule with a stated limit.
- **Not covered**: `UploadArmTest:150` ("The seam exposes no way to clear the ledger or the discovery
  cursor") tests the *seam's shape*, not a producer's behaviour, and passes either way. Nothing mechanized
  the prohibition, which is why it could be false for four days without anything going red.
