## Why

Two facts are currently answered by one structure. *"Have I already uploaded these bytes?"* and *"What do I
currently advertise to the other members?"* are different questions with different lifetimes, and today both
are read off the upload ledger filtered by the membership's live selection policy. Because the policy is
applied at both, a member who narrows their scope — raises their capture cutoff, or turns sharing off —
changes the answer to the *first* question too, and loses upload-suppression state that has nothing to do
with what they share.

That conflation has three visible consequences, and it is also why `SelectionPolicy` carries a special-cased
capture floor and a two-variant sum type: `UploadCycle`'s direction gate exists largely to stop a
non-contributing membership from blanking its own published manifest, and the type exists largely to make
that gate's question answerable.

Separating the two facts removes the conflation, and the type simplification follows from it rather than
being an end in itself.

> **Verification honesty.** The two defects below (**B**, **C**) are established by **code trace, not by an
> observed failure**. The first two implementation tasks pin current behaviour in `commonTest` and are
> expected to go **red**. If either passes, the corresponding claim is wrong and that part of this proposal
> must be revised before any production code changes.

## What Changes

**A. `SelectionPolicy` becomes a plain collection of rules.**

- **BREAKING** (internal): the sealed two-variant type (`None` / `Admitting(cutoff, rest)`) is replaced by a
  single `Policy(rules: List<SelectionRule>)`. `admits(facts) = rules.all { it.admits(facts) }`. The policy
  enforces nothing about the capture floor.
- New rule `SelectionRule.DenyAll` (`admits = false`) replaces the `None` variant. A download-only membership
  gets a rule list containing it.
- The invariant *"a contributing membership always carries a capture floor"* moves to the **build site**: the
  one derivation always emits `CaptureAfter(config.minPhotoDate)`, and `minPhotoDate` is a non-null field of
  `EventConfig`. The policy type no longer asserts it.
- The two-phase construction (`from()` then `excluding()`) collapses into **one** rule-builder. It gates on
  `config.direction.includesUpload` internally, so a non-contributor invokes neither port reader. **Rule**
  construction may be `suspend`; **policy** construction is not. `excluding()` is deleted.
- `enumerates` is removed. The expensive walk is the predicate-bearing path, which `DenyAll` narrows to
  nothing; the predicate-less paths are bounded deltas by construction.
- The iOS predicate translator gains a `DenyAll` arm emitting a **zero-row** predicate.

**B. Narrowing becomes retractive at the manifest; leaving stays non-retractive.**

- **BREAKING** (user-visible): reconfiguring to a narrower scope — raising the cutoff, or turning sharing off
  — now **updates the device manifest** to match the new policy, removing the listings that fall outside it.
  This inverts the current requirement that a narrowing change never retracts.
- **Leaving is unchanged.** The departed `.left.json` freeze stays exactly as specified; a member's
  contributions survive their departure.
- The reconfigure surface's inline helper text changes to say so — and must state that the retraction is
  **partial by nature**: SnapSync syncs gallery-to-gallery, so a member who already downloaded a photo holds
  it in their own library and nothing reaches it. Narrowing removes the listing and stops future syncs; it
  does not un-share from anyone who already has it.

**C. The ledger stops depending on the selection policy.**

- **Ledger** = every resource whose bytes are on the backend for this event. **Never pruned.** Nothing on
  the device deletes an uploaded byte, so a `COMPLETED` row is true until the event expires.
- **Manifest** = the ledger filtered by what the rows can answer — the capture-date bounds, the two id-set
  exclusions, and the absence mark below — re-projected each cycle. Narrowing shrinks it; widening restores
  it; **neither re-uploads**.
- **BREAKING** (internal): `retainAssets` is **removed**, and `deleteByAssetId` becomes a **mark**. An asset
  gone from the library keeps its row (its bytes are still on the backend) and gains an absence fact the
  projection excludes on. Marking is idempotent and self-correcting where pruning was destructive, and
  upload suppression now survives a delete-then-restore — plausible within one event, since iOS's Recently
  Deleted holds 30 days and an event lives at most 30.
- The full-enumeration deletion backstop goes with it. Deletions have a precise signal
  (`discovery.removedAssetIds`), and a missed deletion is now harmless: the row stays listed, the bytes are
  still there, so the download succeeds — the photo simply stays in the event, exactly as when a member
  leaves.

**D. The upload cycle's direction gate shrinks to what it still justifies.**

- The gate stops withholding the manifest write. A download-only membership publishes an empty manifest,
  which is what **B** now wants.
- The terminal-job settle pass stays ahead of everything, unchanged.
- The re-join reconcile moves **ahead** of the gate: it establishes which bytes are already on the backend,
  a fact this change defines as policy-independent, and it is marker-gated so the cost is bounded to the
  first cycle after a join, switch, or reinstall.

## Capabilities

### New Capabilities

None. This change redistributes responsibility across existing capabilities rather than adding one.

### Modified Capabilities

- `photo-selection-policy`: the policy becomes a flat rule list; `DenyAll` joins the sealed rule vocabulary;
  the capture floor stops being a field of a contributing variant and becomes a rule the single derivation
  always emits; the requirement naming two states as *unrepresentable* is rewritten, since the floor
  invariant now holds at the build site rather than in the type; the two-phase construction requirement is
  replaced by one derivation.
- `reconfigure-membership`: **inverts** the narrowing rule — a narrowing change (raising the cutoff, or
  turning a direction off) now *does* retract the member's manifest listings, while leaving does not; the
  inline helper-text requirement changes accordingly and must carry the partial-retraction caveat; the drain
  requirement is unchanged but its stated purpose is now actually delivered, because retention no longer
  discards the drained rows.
- `device-manifest`: the projection applying the membership's live policy becomes the *intended* mechanism
  for reflecting scope changes rather than an incidental filter; an empty projection is a valid manifest and
  is published; deletion-awareness comes from the row's absence mark rather than from pruning, and the
  full-enumeration deletion backstop is removed.
- `sync-ledger`: the ledger is **never pruned**. `retainAssets` is removed and `deleteByAssetId` becomes a
  non-destructive mark, so a row records "these bytes are on the backend" for the event's whole life and a
  scope change never discards upload-suppression state.
- `upload-lifecycle`: the direction gate no longer withholds the manifest write; its documented
  justifications are corrected to the ones that hold.
- `gallery-status`: the platform fetch translator must express a deny-everything policy as a zero-row
  predicate.

## Impact

**Code**

- `:domain` `model/` — `SelectionPolicy.kt` (type collapse, `DenyAll`, single rule-builder, `excluding()`
  deleted), `DeviceManifest.kt` (projection intent).
- `:domain` `feature/upload` — `UploadCycle` (direction gate scope, `retainAssets` input), `LedgerWriter`.
- `:domain` `feature/membership` — `ReconfigureEvent` (a stale comment claiming a raised cutoff "un-shares
  nothing" becomes false), `DeviceManifestProducer`.
- `:domain` `feature/status` — `ShareableCount`, `OwnDeviceGalleryStatusSource` (both currently exhaust the
  sealed type or read `enumerates`).
- `:domain` `ports/` — `LedgerStore`: `retainAssets` removed, `deleteByAssetId` becomes a mark.
- Ledger schema migration for the absence fact, in the same family as the existing 4.sqm/5.sqm.
- `:adapter:ios:ext-safe` — `PhotoKitCandidateSource.predicateFor` (new `DenyAll` arm).
- `:adapter:generic:fake` — `InMemoryCandidateSource` (exhausts the sealed type today).
- `:ui:screens` / `:ui:presentation` — reconfigure surface helper text.
- `:test:rig` — `GalleryReader` derives `admitted` by re-running the rule list instead of asking
  `policy.admits`; non-gating and test-only, so nothing catches it if missed.

**Not affected**

- `leave-event` and `event-leave-endpoint`. The `.left.json` freeze is unchanged. An option to remove the
  manifest on leave is a named **non-goal** of this change.
- The backend. No endpoint, payload, or storage layout changes.

**Verification**

- Linux: `./gradlew build` (domain `commonTest` on JVM) and `compileIosMainKotlinMetadata` on
  `:adapter:ios:ext-safe` and `:test:rig`, which compile-check the predicate translator and the rig reader.
- macOS CI only: `PhotoKitCandidateSourceTest` (`iosTest`; it calls `NSPredicate.predicateWithFormat`).
- Device measurement recommended before shipping: that the zero-row predicate really returns zero rows. All
  three constraints already documented above `predicateFor` are cases where a plausible predicate did
  something else — one silently returned zero rows, two aborted the process.
