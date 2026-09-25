## MODIFIED Requirements

### Requirement: Delivery gates on the test suite

TestFlight delivery SHALL be performed by a dedicated `ios-deliver` job in `.github/workflows/ios.yml` that declares `needs: [ios-build, ios-test, ios-contracts]` — **every** merge gate capability `ios-ci` names, and no other job. The job SHALL run **only** when **all three** merge gates conclude successfully on that commit; if the device build, the simulator test suite, or the in-app port contracts and all-real journeys fail, `ios-deliver` SHALL NOT run and **nothing SHALL be uploaded to TestFlight**.

The dependency list SHALL track the merge-gate set: a job that joins the merge gates SHALL join `ios-deliver`'s `needs:` in the same change. A merge gate that delivery does not consult reopens the hole below for whatever that gate checks — which is how `ios-contracts` shipped as the third gate while delivery still consulted only two.

This closes a hole in the previous shape, where export and upload lived inside `ios-build` — a job with no dependency on `ios-test`. A commit whose test suite was red on `main` was still delivered to testers, because the build job neither knew nor cared about the test job's result.

#### Scenario: A red test suite stops the release
- **WHEN** a commit on `refs/heads/main` compiles (so `ios-build` is green) but the `ios-test` simulator suite fails
- **THEN** the `ios-deliver` job does not run and no build is uploaded to TestFlight

#### Scenario: A red in-app contract run stops the release
- **WHEN** a commit on `refs/heads/main` has `ios-build` and `ios-test` green but `ios-contracts` fails — a port contract clause, a refusal, or a journey
- **THEN** the `ios-deliver` job does not run and no build is uploaded to TestFlight

#### Scenario: A red build stops the release
- **WHEN** a commit on `refs/heads/main` fails to compile
- **THEN** the `ios-deliver` job does not run and no build is uploaded to TestFlight

#### Scenario: All three gates green delivers
- **WHEN** a commit on `refs/heads/main` has `ios-build`, `ios-test` and `ios-contracts` all green
- **THEN** `ios-deliver` exports an `app-store-connect` signed IPA from `ios-build`'s archive and uploads it to TestFlight via App Store Connect

### Requirement: Signed device build delivered to TestFlight on a delivering run

The system SHALL deliver a signed iOS build to **TestFlight** only on a **delivering run** — a push to **`refs/heads/main`**, or a deliberate **`workflow_dispatch`** on any ref (capability `ios-ci`); on any **other** ref's push no export and no upload occur. The signed **archive** itself SHALL still be produced on **every** ref (it is the `ios-build` merge gate — see capability `ios-ci`).

A dispatched delivery SHALL be subject to **every** rule this capability states for a `main` delivery, without exception: it depends on every merge gate, it is Release/production-APNs, it carries the DSN, it takes the next monotonic build number, it retains its dSYMs, and it reaches only the internal group. That uniformity is the point — a probe build that behaved differently from a delivered one would answer a question about a build nobody ships.

The device (`iosArm64`) app SHALL be compiled exactly **once** per push: `ios-deliver` consumes the archive `ios-build` published as a workflow artifact and **re-signs and packages** it, and SHALL NOT recompile the app. Per-branch device installability before merge is **not** served by TestFlight; it is served **out of band** by the interactive dev build loop (dev infrastructure; see the `ssh-mac-build` skill), not by any CI artifact. Both jobs SHALL run on a `macos-26` hosted runner with the runner's GM Xcode.

#### Scenario: A push to a non-main branch does not upload to TestFlight
- **WHEN** a commit is pushed to any ref other than `refs/heads/main`, and the run is not a dispatch
- **THEN** `ios-build` still archives the device app (the merge gate) but publishes no archive artifact, and `ios-deliver` does not run

#### Scenario: A dispatched branch run delivers like main
- **WHEN** an operator dispatches the workflow on a branch and all three merge gates are green
- **THEN** `ios-deliver` exports and uploads that branch's build to the internal TestFlight group, under every rule a `main` delivery obeys

#### Scenario: The device app is compiled only once per push
- **WHEN** a commit is pushed
- **THEN** the device (`iosArm64`) framework is compiled exactly once — as `ios-build`'s signed archive — and `ios-deliver` re-signs and packages that same archive rather than compiling a second time

### Requirement: Delivery never blocks merges, and never fails silently

Delivery SHALL be decoupled from the merge gates **structurally**: it lives in a separate `ios-deliver` job that never runs on a pull-request branch's push and posts **no required status check** (the branch ruleset on `main` requires `build`, `ios-build`, `ios-test` and `ios-contracts`, and SHALL NOT require `ios-deliver` — a job that never runs on a pull-request branch would, if required, freeze every merge). Because it can block nothing, `ios-deliver` SHALL NOT use `continue-on-error`: a failed export or a failed App Store Connect upload SHALL conclude the job as **failure (red)**, so a broken delivery is visible rather than hidden inside an otherwise-green run.

This replaces the previous `continue-on-error` convention, under which a transient delivery failure left the run green and could pass unnoticed.

#### Scenario: A delivery flake is red but blocks nothing
- **WHEN** all three gates are green on `main` but the export or the TestFlight upload fails
- **THEN** the `ios-deliver` job concludes as failure (red) and the failure is plainly visible, while no merge is blocked (the commit is already merged and `ios-deliver` is not a required check)

#### Scenario: A compile failure still fails the gate
- **WHEN** the signed archive fails to compile
- **THEN** the `ios-build` status check concludes as failure (red)
