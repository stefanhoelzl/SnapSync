# Tasks

Prerequisite: phase 4.4b's move II has landed on the branch — `SettlePresentedJobs`, `OsQueueDrain`, `OsQueueTier` and
`ExtensionPass` exist in the layers.

## 1. The failing behaviour, against today's code

- [x] 1.1 Add the integration scenario "A photo no longer shared is not sent again after its upload failed" (`@Verifies`
  photo-sharing, that scenario): extension uploads only, joined, a photo's job fails (`uploadQueue` fails it, so it
  comes back offered for retry), the member narrows the range past the photo (or switches to receiving only), a
  `cycle()` — then no re-point and no re-created job for it, and no `BytesStored`; widening the range back and a
  `cycle()` → its job is created and lands. Verify: it FAILS on the branch before task 2 (the retry is re-pointed), and
  `./gradlew :test:architecture:verifiesReport` lists the scenario once the delta is synced.

## 2. The settle judges each retry

- [x] 2.1 `SettlePresentedJobs` takes the pass's `Contribution` instead of `Presented.RUN`/`DECLINED` (`WITHHELD` stays);
  `ExtensionPass` passes it. Verify: compiles; `ExtensionPass`'s three settle calls read the same contribution its walk
  and publish use (D4).
- [x] 2.2 Each offered retry and each held failure is judged once per settle: shared = `policy.contributes` and
  `admitRecorded` admits its row (D1). Verify: unit tests in `:test:data` — an admitted retry is re-pointed and an
  admitted failure re-created, as today.
- [x] 2.3 A refused offered retry is acknowledged without a re-point (`OsQueueDrain.answer`) and its row recorded returned
  through `JobRecord`; a refused held failure is not re-created (D2). Verify: unit tests — the queue saw no retry and no
  create, the offered job was acknowledged, the row is `DISCOVERED`; a later top-up under a widened range creates it.
- [x] 2.4 A declined membership refuses every retry and re-creation (D3). Verify: unit test — with sharing off, nothing
  is re-pointed or re-created, every presented job is still answered; `WITHHELD` unchanged.
- [x] 2.5 KDoc: `SettlePresentedJobs`' "without the policy's word" replaced by the rule and why. Verify: `./gradlew build`
  green; task 1.1 now passes.
