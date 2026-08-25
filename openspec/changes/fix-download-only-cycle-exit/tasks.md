## 1. Preconditions

- [x] 1.1 Confirm `collapse-upload-tier-seam` has merged, then rebuild this change's `upload-lifecycle`
      delta block from the **current** spec text rather than from the text captured when this change was
      written — that change carries its own delta on the same capability, and reverting its wording is
      the failure mode a stale MODIFIED block produces.
      **Checked 2026-08-25: NOT merged** (`origin/main` still at `98f49ef8`); its change is archived on
      branch `os-producer-deregistration` awaiting merge. Measured the actual collision instead:
      **no code-file overlap** (theirs: `UploadArm`, `SnapSyncApp`, `PhotoKitUploadProducer`,
      `UrlSessionUploadController`, `UploadPushReceiver`, `CompositionMode`, `UploadMechanism`; ours:
      `SelectionPolicy`, `UploadCycle`, `OwnDeviceGalleryStatusSource`, `InMemoryCandidateSource`,
      `SentryLogWriter`), and **no requirement overlap** in `upload-lifecycle` — they ADD four and MODIFY
      *Upload producer seam has no destructive verb*, *Lifecycle orchestration is tier-neutral and
      tested*, *Exactly one producer started per process*; this change modifies only *The arm's direction
      gate lives at the choke point, never at the invoker*. The stale-block hazard therefore does not
      arise. Decision: implement groups 2–7 now, **hold group 8 (`/ship`)** until their change merges,
      then rebase and re-run group 7
- [x] 1.2 Re-read `openspec/specs/photo-selection-policy/spec.md` and `openspec/specs/gallery-status/spec.md`
      for the same reason, and confirm the two requirement headers this change modifies still match exactly.
      **Verified 2026-08-25**: all five modified headers match exactly, one occurrence each —
      `Participation direction is a selection input on the policy`, `GalleryStatusSource seam`,
      `The arm's direction gate lives at the choke point, never at the invoker`,
      `A disabling change drains in-flight uploads but cancels in-flight downloads`,
      `Error-severity log lines become events; lower severities become breadcrumbs`
- [x] 1.3 Confirm the four Bugsink issues this change closes are still the four expected
      (`SNAPSYNC-27/28/29/30`) and note their event counts, so the post-merge check has a baseline.
      **Baseline 2026-08-25**: `SNAPSYNC-27` 1 · `SNAPSYNC-28` 3 · `SNAPSYNC-29` 1 · `SNAPSYNC-30` 3 =
      **8 events**; latest `last_seen` 2026-08-23T14:13:36. No other issue carries the "capture floor"
      message. All four are now marked **resolved**, which makes 8.2's check sharper rather than weaker:
      a resolved issue that gains an event **reopens**, so a regression announces itself instead of
      creeping a counter

## 2. Make the missing capture floor unrepresentable

- [x] 2.1 Change `SelectionPolicy.Admitting` in `:domain` `model/SelectionPolicy.kt` to
      `data class Admitting(val cutoff: CaptureCutoff, val rest: List<SelectionRule>)`, with
      `val rules: List<SelectionRule> = listOf(SelectionRule.CaptureAfter(cutoff)) + rest` computed once
      in the body so equality keys on the two constructor parameters
- [x] 2.2 Update `SelectionPolicy.from(includesUpload, cutoff, ceiling)` to store its `cutoff` parameter
      verbatim rather than wrapping it into the rule list
- [x] 2.3 Update `SelectionPolicy.excluding(...)` to rebuild as `Admitting(cutoff, rest + extras)`, keeping
      `None` mapping to `None`
- [x] 2.4 **Delete** `SelectionPolicy.walkFloor`, moving its "liveness, not correctness" rationale onto
      `Admitting.cutoff`'s KDoc (the doc explains why the lower bound alone is required in a platform
      predicate while every other narrowing is advisory)
- [x] 2.5 Update `:adapter:generic:fake`'s `InMemoryCandidateSource` to read the bound via
      `(policy as? SelectionPolicy.Admitting)?.cutoff`, so which case it is handling is explicit
- [x] 2.6 Update the `admitting(vararg rules)` helper in `:adapter:ios:ext-safe`'s
      `PhotoKitCandidateSourceTest` to supply a cutoff (`predicateFor` iterates rules order-independently,
      so no production change is needed there)
- [x] 2.7 Run `./gradlew compileIosMainKotlinMetadata` to catch iOS-only breakage from the type change
      before going further

## 3. Fix both cycle exit points

- [x] 3.1 Replace `UploadCycle.run()`'s inverted pair with one exhaustive `when` over the sealed policy:
      `None` logs the routine skip at `Info` and returns `SKIPPED`; `is Admitting` yields the non-null
      `cutoff`. Delete the `Error` branch and the now-false comment claiming `None` "returned above"
- [x] 3.2 Move the terminal-job acknowledgement pass (`fetchAckJobs` → adjudicate → `acknowledge` → ledger
      settle) **ahead** of the direction gate, leaving the reconcile, the retry pass, discovery, the
      manifest write and the notify behind it.
      **Implemented differently, and the difference is load-bearing.** Hoisting the pass wholesale above
      the gate would put its ledger writes *before* the re-join reconcile settles — which the provenance
      backfill's own comment forbids ("a settled reconcile means the membership this cycle records under
      is the one the marker agrees with; a switch's `resetTo` has already re-baselined"). So the pass was
      **extracted** into `settleTerminalJobs(engine)` and is called from **two** places: the gate's
      declining branch (settle + acknowledge only — no album placement, no notify, since no manifest is
      written for a non-contributor), and its existing position for a contributing membership, whose
      ordering is therefore **unchanged**. A declined cycle discharges the obligation; nobody else is
      reordered. Consequence for 6.1: the `upload-lifecycle` prose "exactly one phase SHALL run ahead of
      it" must be reworded to "a declined cycle SHALL run the acknowledgement pass before returning" —
      the scenarios as written already describe the implemented behaviour
- [x] 3.3 Rewrite Phase 2's justification comment: it currently asserts the skip is safe because `stop()`
      deregistered the extension. State instead what the measurement established — that the premise fails
      on the reconfigure path, and that the pass therefore runs unconditionally
- [x] 3.4 Convert `OwnDeviceGalleryStatusSource.refresh()` to the same exhaustive `when`, removing the
      `cutoff == null` disjunct that is now provably dead

## 4. Stop one cause arriving as four issues

- [x] 4.1 In `:adapter:ios:ext-safe`'s `SentryLogWriter`, capture the bare redacted message and set the
      ambient log context as a scope-local `entry_point` tag, on **both** the `captureMessage` and
      `captureException` paths; keep the `[ctx]`-prefixed text on the preceding error breadcrumb
- [x] 4.2 Confirm by compile that `captureMessage(String, ScopeCallback)`,
      `captureException(Throwable, ScopeCallback)` and `Scope.setTag` are callable in sentry-kmp 0.27.0 —
      the klib declares all three, but a symbol table over-promises and only a compile settles it

## 5. Close the test blind spot

- [x] 5.1 Add a `commonTest` in `UploadCycleTest` asserting a download-only cycle emits **no**
      `Error`-severity line, via a recording Kermit `LogWriter` injected through the cycle's `log`
      parameter (precedent: `SentryLogWriterTest`). This pins the contract that a routine skip never
      becomes a crash report
- [x] 5.2 Add a `commonTest` asserting that a cycle declined by the direction gate **still acknowledges**
      every presented terminal job and settles it in the ledger, while creating no job, writing no
      manifest, performing no enumeration and leaving the discovery cursor untouched
- [x] 5.3 Re-check `a_non_contributing_membership_creates_no_job_and_lists_nothing` and
      `the_gate_precedes_the_reconcile_and_the_walk`: both previously passed through the wrong branch.
      Update `the_gate_precedes_the_reconcile_and_the_walk`'s assertion, which currently expects an empty
      call order — the acknowledgement pass now legitimately runs ahead of the gate
- [x] 5.4 Add an `iosTest` case to `SentryLogWriterTest` asserting the captured event's message carries no
      `[…]` prefix while the entry point is present as a tag (runs on `macos-26` via the required
      `ios-test` job; it cannot run on Linux).
      **Two cases added** (message path and exception path). Not compiled or run locally — `iosTest` is
      macOS-only, so `ios-test` is the first thing that executes them. They rest on a premise that is
      already measured rather than assumed: `crash-reporting` cites "the measurement that a scope tag
      reaches `beforeSend`" (`changes/archive/2026-07-31-add-bug-report-description`)
- [x] 5.5 Check whether any `:test:integration` case asserts on the download-only skip's log text or on
      `walkFloor`, and update it

## 6. Sync the specs

- [x] 6.1 Apply the five delta specs to `openspec/specs/` (`photo-selection-policy`, `upload-lifecycle`,
      `reconfigure-membership`, `crash-reporting`, `gallery-status`)
- [x] 6.2 Correct the stale prose the type change invalidates, if the delta blocks have not already:
      `photo-selection-policy` naming `Admitting(rules)` and describing "an `Admitting` policy carrying no
      `CaptureAfter` rule", and `gallery-status` describing "an `Admitting` policy carrying no capture-date
      lower bound" — both now name a state that cannot be constructed
- [x] 6.3 Run `npx --yes @fission-ai/openspec@1.5.0 validate --specs --strict` (structure only — it has
      never opened a `.kt` file, so green means well-formed, not true)

## 7. Verify

- [x] 7.1 `./gradlew build` — the canonical check; the architecture guards, the shell gates and the JVM
      tests all gate here
- [x] 7.2 `./gradlew compileIosMainKotlinMetadata` — the Linux-runnable proxy for the iOS source sets
- [x] 7.3 `./gradlew architectureDiagrams` and commit if anything changed — stale `architecture/` blocks
      the PR
- [x] 7.4 Confirm the required `ios-test` job passes on the branch: it is the only thing that runs the two
      `iosTest` files this change touches.
      **PASSED** — commit `8ac8b19a` pushed to `origin/download-only-failures`; run `32798722159`,
      job `ios-test` = **success** (3m41s on `macos-26`). `:adapter:ios:ext-safe:compileTestKotlin` →
      `linkDebugTest` → `iosSimulatorArm64Test` all ran fresh (no `UP-TO-DATE`/`FROM-CACHE`), so the two
      macOS-only files this change touches were genuinely compiled and executed for the first time.
      `build` = success, `appstore` = success

## 8. Ship

- [ ] 8.1 Open the PR with exactly one changelog label — `internal`. No customer-visible behaviour changes:
      a download-only membership created no upload job and wrote no manifest before or after; what changes
      is a log severity, a dead branch, a type, an acknowledgement that now happens, and how the operator's
      tracker groups events. Ship via `/ship`
- [ ] 8.2 After the merge delivers to TestFlight, re-run `/bugsink`: `SNAPSYNC-27/28/29/30` must stop
      gaining events, and no new `admitting policy carries no capture floor` issue may appear

## 9. Follow-ups to raise, not to do here

- [ ] 9.1 Raise wiring `onReconfigure` into `:test:rig` as its own change. Its exclusion reads "wired
      nowhere yet because no scenario needs it driven" — this investigation expired that reason, and it is
      why the reconfigure path had to be approximated by voiding the membership instead of driven directly
- [ ] 9.2 Record, wherever the team tracks platform measurements, that the iOS ≥26.1 acknowledgement
      penalty (`Code=50008`, jobs discarded, ~300 s configuration backoff escalating with an attempt count)
      was measured on SE2 / iOS 26.6, n=1, and carries a re-measure trigger at the next iOS major
