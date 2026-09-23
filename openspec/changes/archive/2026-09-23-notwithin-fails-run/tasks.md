## 1. Runner

- [x] 1.1 `verify` (`test/contracts/src/commonMain/kotlin/app/snapsync/contracts/Runner.kt`) counts `Outcome.NotWithin` among the outcomes that fail a run, beside `Failed` and `Diverged`; `RunnerTest` pins it ("verify fails on an expired wait")
- [x] 1.2 `verify`'s KDoc states the rule and why each binding kind's expired wait is a fault, citing "Outcomes are explicit and none is silent"; `NotRunHere`'s sentence stays
- [x] 1.3 `scripts/sim-contracts` also fails on a `Diverged(` row, so it obeys the same one rule (a live binding never diverges, so this changes no outcome), and its header comment cites the requirement

## 2. Measure

- [x] 2.1 `./gradlew build` is green locally (every JVM `verify` binding under the new rule)
- [ ] 2.2 The PR's CI run is green on the `IOS_SIM_KEXE` bindings (the Sentry reporter's wire clauses, the `LinkOpener` and `BackgroundScheduler` replays) and on `ios-contracts`; any clause that reads `NotWithin` is named in the PR as either a regression the hole hid or a bound raised with its measurement

## 3. Ordering

- [ ] 3.1 Tell the `upload-job-contracts` workspace (phase 6b) that an expired wait now fails the run, before 6b merges
