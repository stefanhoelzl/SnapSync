## 1. Workflow

- [x] 1.1 In `.github/workflows/ios.yml`, set `ios-deliver`'s `needs:` to `[ios-build, ios-test, ios-contracts]`
- [x] 1.2 Update the comments in `ios.yml` that restate the two-gate dependency: the header (the `ios-deliver` bullet and
      "Because it needs BOTH gates"), the `workflow_dispatch` "WHY IT IS SAFE TO WIDEN" note, the `ios-build` note that
      delivery "needs BOTH gates green", and the block above `ios-deliver`. Also add `ios-contracts` to the header's job
      list, which still calls itself "Three jobs… TWO are the parallel merge gates"
- [x] 1.3 Update the "PROVENANCE IS AN UPLOAD-TIME GUARANTEE" comment in `.github/workflows/ios-appstore-promote.yml`
      to say every merge gate
- [x] 1.4 `grep -n "ios-build, ios-test\]\|both .*gates\|BOTH gates" .github/workflows/` finds no remaining two-gate claim

## 2. Spec Purposes (not carried by the deltas)

- [x] 2.1 `ios-testflight-delivery` Purpose: "depends on both merge gates (`ios-build` and `ios-test`…)" → every merge
      gate, naming all three, and "a red gate stops the upload"
- [x] 2.2 `ios-ci` Purpose: say three parallel merge gates (naming `ios-contracts`) and that delivery depends on all of them
- [x] 2.3 `ios-appstore-release` Purpose: the provenance sentence says every merge gate
- [x] 2.4 Add `changes/archive/<date>-deliver-needs-ios-contracts` to the three specs' decision-record lists at archive time

## 3. Verify

- [x] 3.1 `npx --yes @fission-ai/openspec@1.5.0 validate deliver-needs-ios-contracts --strict` and `… validate --specs --strict` pass
- [x] 3.2 ~~Dispatch `ios.yml` on the branch (`gh workflow run ios.yml --ref <branch>`) and confirm in the run graph that
      `ios-deliver` starts only after `ios-contracts` concludes green, and uploads~~ — SKIPPED at archive by the operator: the first `main` delivery after merge shows the same run graph
