# Task 1 — outcomes of pinning current behaviour

Three tests written to confirm claims the proposal carried as **code traces, not observed failures**.
Task 1.4 requires halting if any passed.

## 1.1 — Manifest retraction: **CONFIRMED (red)**

`domain/src/commonTest/.../model/NarrowingRetractionTest.kt`, both tests fail.

- `a photo already uploaded stays listed after the cutoff is raised past it` — FAILED
- `a photo already uploaded stays listed after the direction is turned off` — FAILED

The precondition (the photo is listed under the original floor) passed in both, so the failures are the
claim itself: `projectDeviceManifest` re-filters already-`COMPLETED` rows through the current policy, and a
narrowing drops them. This contradicts `reconfigure-membership:213` and `ReconfigureEvent:97`.

**Claim stands.** The user's ruling — that the spec should invert rather than the behaviour — is what this
change implements.

## 1.2 — Ledger pruned by a raised cutoff: **CONFIRMED (red)**

`UploadCycleTest.raising_the_cutoff_does_not_prune_an_already_uploaded_row` — FAILED.

A `COMPLETED` row for an asset still present in the library loses its row on the next fully-drained full
enumeration once the cutoff moves past it, because `retainAssets` is fed the policy-admitted set. Narrowing
is therefore irreversible without re-upload.

**Claim stands.**

## 1.3 — Ledger wiped by turning the direction off: **NOT REPRODUCED (green)**

`UploadCycleTest.turning_the_direction_off_does_not_prune_the_events_rows` — PASSED.

The direction gate returns `SKIPPED` before the discovery walk, so `retainAssets` never runs for a
non-contributing membership and the ledger survives.

**This is not a failed claim — it is a failed task expectation.** `design.md` section C states it
correctly and conditionally: a direction-off *"would, without the direction gate,"* wipe the ledger.
`tasks.md` overstated that as "Expected: RED". The test is kept as a **regression guard**: design D9 leaves
the discovery walk behind the gate, so this must stay green through the change.

`tasks.md` 1.3 corrected accordingly.

## Blocker found while writing these — design D6 is wrong as written

`UploadCycleTest`'s existing `origin_excluded_asset_row_is_pruned` pins that a **screenshot's** ledger row
is pruned by `retainAssets` once it is no longer admitted. That pruning is **load-bearing**, and D6 would
break it.

Why it is load-bearing: `projectDeviceManifest` builds `AssetFacts(assetId, creationDate)` from a ledger
row — the row carries **no origin facts**. `AssetFacts` defaults land on the *admitted* side of every rule,
so `isScreenshot` is false and `ExcludeScreenshots` admits it. **The projection cannot exclude a
screenshot.** Pruning the row is the only mechanism by which an origin-excluded asset leaves the manifest.

D6 says retention takes "the assets the enumeration actually observed in the library" and not the
policy-admitted set. Under that wording:

- the in-memory fake returns screenshots from discovery (it deliberately does not narrow), so an observed
  screenshot would be **retained** — and then **listed in the manifest**, a regression;
- on device the real fetch predicate excludes screenshots, so they would **not** be observed and would be
  pruned — so fake and device would diverge on exactly the behaviour under test.

### Proposed refinement (needs a decision before task 2)

The clean line is not "observed vs admitted". It is **which rules the projection can re-apply**:

| rule | projection can re-apply it? | drives retention? |
|---|---|---|
| capture-date lower/upper bound | **yes** — the row carries `creationDate` | **no** — reversible scope, and the projection applies it anyway |
| origin exclusions (screenshot, screen recording, resolution floors) | no — the row carries no origin facts | **yes** — pruning is their only path out of the manifest |
| echo suppression, denylisted album | no — id sets resolved per cycle | **yes**, same reason |

So retention should prune a row when the asset is gone from the library **or** when a rule the projection
cannot re-apply rejects it — and never merely because the membership's current capture window moved. That
preserves `origin_excluded_asset_row_is_pruned`, fixes 1.2, and keeps narrowing reversible.

This also explains why the current code appears correct: it prunes on the admitted set, which is right for
every rule *except* the date bounds, and the date bounds are precisely the reversible ones.

---

# Resolution (group 2 complete)

The blocker above was resolved by removing retention entirely rather than re-scoping it — see design D6.
`retainAssets` is deleted; `deleteByAssetId` became the non-destructive `markAbsent`; `LedgerEntry` gained
`absent`; ledger migration `6.sqm` adds the column.

## Tests that had to be restated, and what each pins now

Nine, not the one task 2.6 anticipated. All were pinning pruning, directly or through it.

| test | now pins |
|---|---|
| `removed_asset_rows_are_marked_absent_incrementally_by_assetId` | the change feed's signal **marks**; the row keeps its state |
| `mid_upload_deletion_clears_the_stuck_pending_row` | a marked row stops counting as pending, so nothing pins the extension awake |
| `a_full_enumeration_no_longer_reconciles_rows_away` | a row the enumeration did not return is neither removed nor marked — absence is not evidence |
| `a_marked_then_rediscovered_asset_is_not_re_uploaded` | **inverted**: delete-then-restore no longer re-uploads identical bytes |
| `the_projection_re_applies_only_the_rules_a_ledger_row_can_answer` | the id-set exclusions are re-applied; the origin rules are not |
| `the_projection_re_applies_the_capture_date_bounds` | the date bounds are re-applied, because the row carries its capture date |
| `suppressed_downloaded_assets_create_no_job_and_are_not_listed` | echo suppression keeps a stale row unlisted without pruning it |
| `an_origin_excluded_row_is_no_longer_swept_by_a_full_enumeration` | the retroactive origin sweep is gone, deliberately |
| `a_removal_the_change_feed_never_reported_leaves_the_row_alone` (world) | the accepted gap: an unreported deletion leaves the asset listed |

Two fixtures also changed shape:

- `originCycle`'s manifest hook now reads the **real projection** rather than the raw completed rows. Those
  used to agree only because pruning kept the ledger equal to the shared set; with the ledger no longer
  policy-pruned they differ, and what other members see is the projection.
- `SqlDelightLedgerStoreTest`'s bind-variable-limit test retired. It existed because `retainAssets` took a
  keep-set it could not bind into one statement. `markAbsent` takes one assetId and rides the assetId
  index, so the hazard does not arise; replaced with a large-table indexed-UPDATE check.

## The origin-exclusion consequence, confirmed concretely

`TASK-1-FINDINGS` predicted this and two tests made it real: a `COMPLETED` row whose asset an **origin**
rule would now reject keeps its listing, because a ledger row carries no origin facts and `AssetFacts`
defaults land on the admitted side of every rule. The id-set exclusions (echo, denylisted album) are
unaffected — their sets are supplied per cycle, so the projection re-applies them.

Reaching that state needs a row written before the rule existed, and every origin rule predates any event
that can still be live (≤30-day lifetime). It also lands on the harmless side of this capability's stated
asymmetry: a stray visible photo, not an invisible failure. Accepted, and documented at both test sites.

## Verification

`./gradlew build` green (JVM tests, architecture guards, detekt). `compileIosMainKotlinMetadata` green for
`:domain`, `:adapter:ios:ext-safe`, `:test:rig`.
