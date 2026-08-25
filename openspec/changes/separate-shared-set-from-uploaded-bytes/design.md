## Context

`SelectionPolicy` answers *may this asset enter the event?* as one value, applied at four consumers. That
much is right and stays. What this change addresses is that the **same value is also applied to the upload
ledger**, at two places where it does not belong:

- `projectDeviceManifest` filters the ledger's `COMPLETED` rows through the live policy — which is how the
  manifest reflects the member's current scope, and is intended.
- `UploadCycle:341-343` feeds `retainAssets` the **policy-admitted** set (`liveResources`), which prunes
  rows for assets that are still in the library and still uploaded, merely out of the current window.

The second is the conflation. `LedgerEntry` records that bytes exist on the backend; that fact does not
change when a member narrows their scope. The consequences traced:

1. Raising the cutoff drops rows from the manifest projection — contradicting `reconfigure-membership:213`
   ("Raising the cutoff SHALL remain non-retractive — already-shared photos are not un-shared") and
   `ReconfigureEvent:97`'s own comment ("un-shares nothing"). The user has ruled that the **spec** is what
   should change here, not the behaviour: narrowing *should* retract at the manifest.
2. The same narrowing then prunes the ledger rows on the next full enumeration, so re-widening re-uploads
   identical bytes.
3. Turning a direction off would, without the direction gate, produce an empty admitted set and therefore
   `retainAssets(emptySet())` — wiping the event's ledger. That defeats `reconfigure-membership:170-172`,
   where a drained upload is recorded "so re-enabling the direction" does not re-upload.

`UploadCycle`'s direction gate currently masks (3), and masks (1) for the direction case only. Its comment
gives three justifications; two do not survive inspection — the notify is already guarded on `>= 1` real
completion (`:363-370`), and an empty manifest PUT is already what a zero-contribution *contributor* does.
The gate's one live justification is not blanking an existing manifest, which is precisely the behaviour the
user's ruling now wants.

The type simplification follows from this rather than motivating it: the sum type's remaining job was making
the gate's question answerable.

**Constraints carried in from the codebase.** `model/` is the only zone both `feature/upload` and
`feature/status` can see (they are mutually blind), so anything both need lives there. Shells are
wiring-only with zero conditionals (detekt-gated), so a shell may not unwrap a policy. The platform fetch
predicate is an optimization that may return a superset but never a subset. `:test:architecture` pins that
no consumer compares a capture date outside the single admission site.

## Goals / Non-Goals

**Goals:**

- Make "have I uploaded these bytes?" independent of the current selection policy, so a scope change is
  always reversible without re-upload.
- Make the device manifest the single place a scope change is reflected outward, and make that intentional
  rather than incidental.
- Let a member's narrowing take effect for members who have not yet synced, while being honest that it
  cannot reach those who have.
- Reduce `SelectionPolicy` to what it is — a conjunction of rules — with the membership invariant enforced
  where the policy is built.
- Leave the upload cycle's direction gate holding only what it can still justify.

**Non-Goals:**

- **Removing a manifest on leave.** `.left.json` continues to freeze the departing member's contributions.
  An option to remove it is named as a possible future enhancement and is explicitly not built here.
- Deleting bytes. Nothing in this change deletes an uploaded object; reclamation stays with the nightly
  sweep (`scheduled-cleanup`).
- Any backend change. No endpoint, payload, or storage-layout change.
- Designing for a platform without a fetch predicate. Android is a named future; the consequence is stated
  under Risks and not designed around.
- Changing what a *contributing* membership contributes. The admitted set for a given policy is unchanged.

## Decisions

### D1 — `Policy` is a flat rule list; `DenyAll` replaces the `None` variant

```kotlin
class Policy(val rules: List<SelectionRule>) {
    fun admits(facts: AssetFacts): Boolean = rules.all { it.admits(facts) }
}

data object DenyAll : SelectionRule {
    override fun admits(facts: AssetFacts): Boolean = false
}
```

The policy asserts nothing about its contents. A download-only membership's rule list contains `DenyAll`.

*Alternative — keep the sealed `None` / `Admitting` sum type.* It answers "does this contribute?" and "is
there a floor?" by construction rather than by inspection, which is genuinely stronger. Rejected because the
questions it was answering dissolve: the floor invariant moves to the build site (D2) by explicit ruling, and
the direction question shrinks to almost nothing once the manifest write is no longer withheld (D9).

*Alternative — `Policy(rules)` with `init { require(rules.any { it is CaptureAfter }) }`.* Rejected: a
runtime check leaves the state representable and fails at construction, which is not what "unrepresentable"
means — and it duplicates an invariant the single derivation already guarantees structurally.

*Alternative — an `abstract class Policy` with `DownloadOnlyPolicy` / `FullMembershipPolicy` subclasses.*
This is the current sealed design with different names, minus the exhaustiveness: a non-sealed base is open
to subclassing anywhere, so every `when` needs an `else`. Additionally `None` has **two** causes — a
`DownloadOnly` direction *and* a join preview with sharing off — so naming the variant after one of them
would be wrong for the other.

*Rejected, with evidence — removing `CaptureAfter` from the sealed rule set so the floor lived only as a
field of the contributing variant.* This was proposed and dropped during design. Deleting the `CaptureAfter`
arm from `predicateFor` is a **compile error**, measured:

```
e: PhotoKitCandidateSource.kt:177:25 'when' expression must be exhaustive.
   Add the 'is CaptureAfter' branch or an 'else' branch.
```

So the exhaustive `when` already enforces that the floor reaches the platform predicate, and a plain field
would not — Kotlin has no must-read-a-field mechanism. The idea weakens the guarantee it claims to
strengthen. Do not reintroduce it.

### D2 — One rule-builder; the capture-floor invariant lives at the build site

`from()` and `excluding()` collapse into a single derivation. Rule construction may be `suspend`; **policy**
construction is not.

```kotlin
suspend fun selectionRulesFor(
    config: EventConfig,
    suppressedAssetIds: suspend () -> Set<String>,
    albumExcludedAssetIds: suspend (CaptureCutoff) -> Set<String>,
): List<SelectionRule>
```

It gates on `config.direction.includesUpload` **internally** and returns `listOf(DenyAll)` without invoking
either reader, preserving today's property that a non-contributor pays no I/O to learn it contributes
nothing. On the contributing path it always emits `CaptureAfter(config.minPhotoDate)` — and `minPhotoDate` is
a non-null field of `EventConfig`, so a floorless contributing policy cannot be derived.

This is the ruling that the invariant belongs where the policy is built, not in the policy type. The
consequence, stated as a chosen one: `Policy(listOf(ExcludeScreenshots))` compiles. The single derivation
cannot produce it.

*Alternative — keep two phases.* Rejected: it is why `excluding()` is public, why consumers hold a half-built
policy, and part of why the cutoff has to be extracted back out of it.

*Alternative — one non-suspend `from()` taking the two id sets as values.* Rejected: the caller must then
perform both port reads before it can build anything, including for a download-only membership where the
platform album fetch is pure waste. The cheap gate is the reason the split existed; moving it inside the
builder preserves it.

### D3 — The builder lives in `model/`, and is the first effectful-shaped function in that zone

`feature/upload` and `feature/status` are mutually blind, so the one derivation both need must sit in
`model/`. The builder is `suspend` and takes two effectful lambdas, but **imports no ports** — the lambdas
are injected — so the model-purity gate still passes. Stated here explicitly rather than left for a reviewer
to discover: this is a deliberate widening of what `model/` holds, justified by the zone constraint, and it
remains fully testable in `commonTest` on both targets.

### D4 — `predicateFor` gains a `DenyAll` arm emitting a zero-row predicate

The arm emits `creationDate < %@` against a distant-past date. This reuses the key and operator form already
device-verified by the floor and ceiling clauses.

It deliberately does **not** use `(mediaSubtypes & N) == 0`, which the file documents as returning zero rows
silently. That is an artefact of PhotoKit's predicate parser, not a contract: if Apple ever made it evaluate
correctly, `DenyAll` would begin admitting the whole library — the worst possible direction for this
capability. A predicate built from a comparison that is simply never true does not depend on a quirk.

Note this arm is a **narrowing optimization only**, consistent with the capability's existing rule: `admits`
returns `false` for every asset regardless, so an untranslated `DenyAll` would cost performance, never
correctness.

### D5 — `enumerates` is deleted

Its job was keeping the ~110 ms/asset walk off a non-contributing membership. With D4 that is covered where
it matters: `IosDiscovery.discover:81-87` shows the whole-library enumeration (cold start, no cursor) is the
**predicate-bearing** path, which `DenyAll` narrows to nothing. The two predicate-less paths are bounded by
construction — the incremental walk fetches by identifiers that came from the change feed (a delta), and the
`LIMITED` observer holds the hand-picked selection.

*Alternative — keep it as `rules.none { it.deniesEverything }` with a declared property on the rule
interface.* Rejected once D9 removed the caller that needed it. It would also have carried a silent
wrong-side default (a future always-denying rule inheriting `false`) and made `DenyAll` state one fact twice,
with nothing checking the two agreed.

### D6 — The ledger is never pruned; a departed asset is marked, not deleted

**`retainAssets` is deleted outright.** Not re-scoped — removed. So is the destructive
`deleteByAssetId`, which becomes a mark.

The reasoning is that pruning was never a requirement in its own right. Nothing on the device deletes an
uploaded byte: `scheduled-cleanup` is "the **one** mechanism that reclaims backend storage, and the **only**
thing that deletes an event", and `event-leave-endpoint` deletes no bytes either. So a `COMPLETED` row is
true from the moment it is written until the event dies, and nothing that happens locally can falsify it.
Pruning only ever changed the **listing** — which is the manifest's job.

Pruning was doing two jobs, and neither survives inspection:

- **Deletion-tracking.** Replaced by a mark. A row for an asset no longer in the library keeps recording
  that its bytes are on the backend (still true) and gains a fact saying the asset is gone. The projection
  excludes marked rows — and it *can*, because unlike origin facts the row now carries this one.
- **Origin exclusion.** This was pruning's only remaining justification, and it is nearly empty. The
  projection already re-applies every rule a row can answer: the two capture-date bounds (the row carries
  `creationDate`) and the two id-set exclusions, `NotEcho` and `NotInDenylistedAlbum` (supplied per cycle).
  The only rules it cannot re-apply are the **origin** ones — screenshot, screen recording, resolution
  floors — and those are stable properties of an asset decided at upload time. A row they would now reject
  is one written by a build predating that rule, and every such rule predates any event that can still be
  live (events expire in ≤30 days). Accepted as a consequence rather than designed around.

**The full-enumeration backstop goes with it.** Deletions have a precise signal — `discovery.removedAssetIds`
from the change feed, handled at `UploadCycle:302`. `retainAssets` existed only as "the backstop for
deletions missed while the change token was expired", and a missed deletion is now harmless: the row stays
listed, the bytes are still on the backend, so a member downloads it successfully. No 404. The photo simply
stays in the event — exactly what happens when a member leaves. Deletion-tracking does not need to be
exhaustive once it is no longer also carrying origin exclusion.

This is what removes the hazard that blocked implementation. An earlier version of this decision kept
retention and scoped it to "rows inside the window the walk covered". That fails: the fetch predicate is
policy-narrowed on device but the in-memory fake deliberately does not narrow, so "what the walk returned"
would mean different things in each — and the operation was destructive, so being wrong was unrecoverable.
Marking is idempotent and self-correcting; the question of *which* rows to touch stops being dangerous.

*Alternative — keep an exhaustive backstop that marks rather than prunes.* Safe now that the operation is
non-destructive (mark inside the enumerated window, unmark on re-observation). Rejected for this change as
unnecessary: it buys exhaustive deletion-tracking, whose failure mode is a photo staying shared, which the
system already tolerates on every other path.

*Alternative — keep only the manifest's assets in the ledger.* Rejected: it defeats
`reconfigure-membership:170-172` outright ("recorded in the ledger, **so re-enabling the direction later
does not re-upload that resource**"), because turning share off would empty the manifest and therefore the
ledger, and re-enabling would re-upload the member's entire contribution. It also makes every widening
expensive, and collapses the ledger and the manifest into one set under two names.

**Schema.** `LedgerEntry` gains the absence fact; a ledger schema migration in the same family as the
existing 4.sqm/5.sqm. Rows accumulate rather than disappear, bounded by what the device uploaded for an
event whose life is ≤30 days.

**Side benefit.** Upload suppression now survives a deletion. iOS's Recently Deleted holds 30 days — the
same order as an event's lifetime — so delete-then-restore inside one event is entirely plausible, and
today it re-uploads.

### D7 — Narrowing retracts at the manifest; leaving does not

The manifest is `ledger ∩ current policy`, re-projected every cycle. A narrowing shrinks it; a widening
restores it from rows D6 no longer prunes; neither re-uploads.

Leaving is untouched: `event-leave-endpoint` renames the manifest to `.left.json` and deletes no bytes, so a
departing member's contributions survive. The asymmetry is deliberate and comprehensible — *narrowing* says
"this is what I share now", *leaving* says "I am done", and the second is not a retraction request.

**The retraction is partial, and the UI must say so.** SnapSync syncs gallery-to-gallery: a member who has
already downloaded the photo holds it in their own library, and no manifest change reaches it. Narrowing
removes the listing and stops future syncs. The helper text must not imply more than that.

### D8 — The manifest is published only from a ledger the cycle believes complete

This decision exists *because* of D7. Once the manifest is retractive, **any path that projects from an
incomplete ledger becomes a retraction hazard** — publishing a short manifest silently un-lists bytes that
are really there.

The known such path is the re-join reconcile (`event-rejoin-reconciliation`), which seeds the ledger from the
device's stored-file listing and returns `false` to defer when that listing fails or times out. Today a
deferral skips job creation and returns `COMPLETED`. Under D7 it must **also skip the manifest write**: an
un-seeded ledger projects to a manifest missing rows that exist on the backend.

The general rule: the cycle writes a manifest only on a path where the ledger is known settled. A deferral,
or a ledger read failure, suppresses the write rather than publishing a smaller set.

### D9 — The direction gate shrinks; the reconcile moves ahead of it

Of the gate's three documented justifications, two do not hold (the notify is already completion-gated; an
empty manifest PUT is already what a zero-contribution contributor does) and the third — not blanking an
existing manifest — is the behaviour D7 now wants. So the gate stops withholding the manifest write, and a
download-only membership publishes an empty manifest.

The **terminal-job settle pass stays ahead of everything**, unchanged. Its justification is a measurement, not
a preference: on iOS 26.6, a cycle returning before it made the system report `com.apple.photos.error
Code=50008` ("appex failed to acknowledge jobs for processing state"), discard the outstanding jobs, and
record a failed attempt that deferred the extension ~300 s.

**The re-join reconcile also moves ahead of the gate.** It establishes a fact about *bytes on the backend* —
exactly the fact this change defines as independent of the selection policy — so gating it on direction would
repeat the conflation being removed. It is marker-gated and a no-op on a settled join, so the cost is one
listing on the first cycle after a re-join, switch, or reinstall. Running it early means a member who later
re-enables sharing re-uploads nothing; deferring it to that moment would work too, but only by making a
policy-independent fact wait on a policy-dependent branch.

What remains behind the gate: job creation, and the walk (already free via D4).

## Risks / Trade-offs

- **Deletion-detection stops being exhaustive** → If the change token expires while a photo is deleted,
  that photo stays listed for the event's remaining life. Its bytes are still on the backend so it still
  downloads; the failure mode is "stays shared", which every other path already tolerates. Mitigation if
  ever needed: a non-destructive marking backstop (D6, rejected alternative).
- **Legacy origin-excluded rows stay listed** → A row written before a given origin rule existed keeps its
  listing, because the projection cannot re-apply origin facts. Every such rule predates any event that can
  still be live (≤30-day lifetime), so the reachable population is empty in practice.
- **Both motivating defects are code traces, not observed failures** → The first two implementation tasks pin
  current behaviour in `commonTest` and are expected to go red. If either passes, that claim is wrong and the
  corresponding part of this design must be revised before any production edit.
- **The zero-row predicate is reasoned, not measured** → Every one of the three constraints already
  documented above `predicateFor` is a case where a plausible predicate did something else: one silently
  returned zero rows, two aborted the process. Measure on device before shipping. Until then the consumer
  never passes a `DenyAll` policy to a full fetch in practice, so the blast radius is a performance
  regression rather than a correctness one.
- **Users may read "no longer shared" as "gone"** → The retraction cannot reach members who already synced.
  Mitigated by the helper-text requirement in D7; the wording is a product decision that should be reviewed
  rather than inherited from this document.
- **Narrowing now has an outward effect where it previously had none** → A member who raises their cutoff to
  tidy their own view will remove listings for everyone who has not yet synced. That is the intended
  behaviour, but it is a behaviour change for existing installs and belongs in the release notes.
- **The floor invariant is now a property of one function rather than of a type** → A hand-rolled
  `Policy(...)` bypassing the derivation would be floorless and unbounded. Mitigation options, to be settled
  in tasks: a private constructor with factory doors, or a `:test:architecture` gate that no `Policy(` is
  constructed outside the derivation — the codebase's established pattern for invariants the compiler cannot
  express.
- **`Policy([DenyAll, CaptureAfter(x), …])` is representable** → Harmless, since the rules conjoin and
  `DenyAll` wins. Accepted as the cost of the flat list; the requirement naming this state unrepresentable is
  rewritten rather than worked around.
- **A future platform with no fetch predicate would walk the whole library for a `DenyAll` policy** → Stated,
  not designed for. Android is a named future and this codebase does not build for those. Whoever adds a
  second platform re-decides whether a consumer-side short-circuit is needed.
- **`:test:rig`'s `GalleryReader` is non-gating** → It derives `admitted` by re-running the rule list rather
  than asking `policy.admits`, so nothing catches it if the update is missed. Fixing it to ask the policy is
  both the correction and a small improvement, and it must be an explicit task rather than a follow-the-
  compiler edit.

## Migration Plan

No data migration. `EventConfig`, the ledger schema, the manifest JSON, and every wire payload are unchanged;
this change moves where a filter is applied, not what is stored.

Rollout is a normal merge. The one observable transition for an existing install: on the first cycle after
update, a member whose current policy is narrower than their published manifest will have that manifest
shrink to match. That is the intended behaviour of D7 arriving, not a migration step, and it is
self-correcting in the other direction — widening restores the listings from ledger rows D6 preserves.

Rollback is a revert. A manifest published under the new behaviour is re-published under the old one on the
next cycle, since the manifest is write-only and recomputed each cycle; no durable state is left behind in a
shape the previous build cannot read.

## Open Questions

- **Which mechanism holds the floor invariant at the build site** — a private constructor with factory doors
  (type-enforced, needs `@ConsistentCopyVisibility` or hand-written equality), or a `:test:architecture` gate
  (the established pattern, but evidence rather than a type). To be settled when writing tasks.
- **Exact helper-text wording** for the partial-retraction caveat. A product decision, not a technical one.
- **Whether `ShareableCount` should still answer without touching the source** for a non-contributing
  membership. With `enumerates` gone it would issue one zero-row fetch instead of returning `0` outright —
  cheap, but it is a behaviour change in a preview surface and worth a deliberate answer.
- **Whether a deleted-from-library asset should leave the manifest at all**, given its bytes remain on the
  backend and other members may not have synced it. Current behaviour (it leaves, via ledger pruning) is
  preserved by this change; the question is noted because D6/D7 make it newly visible, not because this
  change alters it.
