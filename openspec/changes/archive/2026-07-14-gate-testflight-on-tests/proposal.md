## Why

**A red `main` shipped to TestFlight.** `ios.yml` run **#297** (commit `40a6ee2`, 12 Jul 2026) concluded
with `ios-build` **green** — so build 297 was delivered to testers — and `ios-test` **red** on the very
same commit (`create_event_lifts_the_setup_gate[iosSimulatorArm64]`, `19 tests completed, 1 failed`).

Nothing malfunctioned. The pipeline did exactly what it was built to do, and that is the problem:

- Branch protection **already** requires `ios-test` (capability `branch-protection`), so the *merge* gate
  was never missing. The hole is downstream of the merge.
- Export + upload to TestFlight lived **inside the `ios-build` job**, which has **no `needs:` on
  `ios-test`**. The two jobs are deliberately parallel and independent. So on the post-merge `main` push,
  `ios-build` archived, exported, and uploaded **without ever consulting the test job's result**. A green
  build ships whatever it built, and a red test suite on the same commit cannot stop it.

The specific red that exposed this was a **flaky test**, not a regression — the tree that failed on `main`
is **byte-identical** (`9679731…`) to the tree that passed `ios-test` on its own branch nine minutes
earlier, and the flake is fixed separately in this PR's first commit. But the flake is the *messenger*.
The hole is real and independent of it: **any** genuinely broken `main` — a semantic conflict between two
individually-green PRs, a test that only fails post-merge — is delivered to real testers' devices with
nothing in the way. Delivery must consult the test suite, whatever reddened it.

A secondary defect surfaced while reading the job: the export and upload steps carry
`continue-on-error: true`, so a **failed TestFlight delivery leaves the run green**. Delivery failures are
currently silent — the opposite of the "still visible in the run" the comment claims, in any workflow view
that shows only the job's conclusion.

## What Changes

- **Delivery moves into its own job, `ios-deliver`, that `needs: [ios-build, ios-test]`** and runs on
  `main` only. If either gate is red, it does not run and **nothing reaches TestFlight**. This is the
  whole change: a red test suite now stops the release.
- **`ios-build` becomes a pure gate.** It archives (the compile that *is* the merge gate) and nothing
  else — no export, no upload, no Apple contact at all. On `main` it hands the signed archive to
  `ios-deliver` as a workflow artifact.
- **The archive is tarred across the hand-off.** `upload-artifact` zips its payload and does not preserve
  executable bits or symlinks; restoring a `.app` through that round-trip would corrupt the signed bundle
  and break `xcodebuild -exportArchive`. Packing with `tar` preserves the modes.
- **`continue-on-error` is deleted.** It is no longer needed, and it was hiding failures.
  "Delivery never blocks merges" becomes **structural**: `ios-deliver` runs only on `main`, post-merge,
  and posts **no required status check**, so it *cannot* block a merge no matter how it concludes. Freed
  from that constraint, it is allowed to fail **red** — a broken delivery is now visible instead of green.
- **The two merge gates stay parallel and independent.** Adding `needs: ios-test` to `ios-build` was
  rejected: it serializes every push, and a red test would *skip* `ios-build`, whose required check would
  then never be posted — freezing merges outright. See `design.md`.

**Not changed:** the branch ruleset. `ios-deliver` must **not** become a required check — it never runs on
a pull-request branch, so requiring it would freeze every merge on a check that can never appear. The
required contexts remain `build`, `ios-build`, `ios-test`.

## Capabilities

### New Capabilities

None. Two existing capabilities change what they guarantee.

### Modified Capabilities

- `ios-testflight-delivery`: **new requirement** — *Delivery gates on the test suite*: delivery runs in
  `ios-deliver` with `needs: [ios-build, ios-test]`; if either gate fails, nothing is uploaded.
  **Modified** — delivery is main-only via the new job, and the device app is still compiled exactly once
  (the delivery job re-signs and packages, never recompiles). **Rewritten** — *Delivery is decoupled from
  the merge gate* becomes *Delivery never blocks merges, and never fails silently*: decoupling is now
  structural (a separate, non-required, `main`-only job) rather than a `continue-on-error` convention, and
  a failed delivery concludes **red**. Signing requirements now bind **both** jobs that run
  `-allowProvisioningUpdates`, so neither can mint a cert.
- `ios-ci`: **modified** — `ios-build` is a pure gate that exports and uploads nothing; on `main` it
  publishes the signed archive as a workflow artifact (packed, to survive the round-trip). **New
  requirement** — *The merge gates are exactly the two parallel jobs*: `ios-build` and `ios-test` are the
  only iOS required checks, they must not gain a `needs:` between them, and `ios-deliver` must never be
  made a required check.

## Impact

- **`.github/workflows/ios.yml`** — `ios-build` loses its export + upload steps and gains a pack + publish
  step on `main`; a new `ios-deliver` job downloads, unpacks, exports, and uploads. `ios-test` is
  untouched.
- **No product code changes.** This is CI policy only; the app binary delivered is bit-for-bit the archive
  `ios-build` already produced.
- **`.github/rulesets/main.json` is deliberately untouched** (see above).
- **Concurrency is deliberately untouched.** `cancel-in-progress: true` still applies to `main`, so a
  rapid second merge can cancel the first commit's delivery. Accepted: merges to `main` are serialized by
  the PR queue, build numbers are monotonic, and the next merge ships a superseding build.
- **Cost**: one extra `macos-26` job on `main` pushes only. It compiles nothing (no Java/Gradle/Konan
  setup) — it re-signs and uploads — so it is short, and it runs only after both gates are already green.
- **Verification**: the gating is structural (`needs:`), so it is provable by inspection — but the
  archive-through-artifact hand-off is the one genuinely new mechanism and is confirmed by the first
  `main` delivery after merge (a build appears in TestFlight with `CFBundleVersion` = the run number).
