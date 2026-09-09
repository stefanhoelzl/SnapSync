## 1. Confirm the facts before editing the contract

- [ ] 1.1 Re-read `api/src/db.ts` `publishStatements` — confirm the `if (!opts.legacy) continue;` guard is
      what skips the `resources` upsert, and that only the v1 route passes `{ legacy: true }`.
- [ ] 1.2 Re-read `api-endpoints` lines ~129–136 and ~280–290 — confirm it already states the v1 repair and
      the v2 absence, so this change corrects `database` alone and introduces no third account.
- [ ] 1.3 Confirm with the operator that the deployment is a single primary with no read replica, and
      record where that was confirmed. The replica edit rests on it.

## 2. Correct the database spec

- [ ] 2.1 Apply the *The database holds only rebuildable state* delta: separate reconstructibility from
      automatic repair, state the v2 upload-record path, and name the uncovered case plainly.
- [ ] 2.2 Remove "There SHALL be no dedicated reconciliation pass", replacing it with the narrower rule
      that no repair may be assumed as a side effect of an unrelated write.
- [ ] 2.3 Apply the *Replica staleness* delta: state the single-primary deployment and the analogy the
      caution came from, keep the sweep's primary-transaction discipline, and make the guard explicitly
      conditional on a topology change.
- [ ] 2.4 `npx --yes @fission-ai/openspec@1.5.0 validate --specs --strict`.

## 3. Correct the one stale comment in Kotlin

- [ ] 3.1 In `domain/feature/upload/Reconciler.kt`, rewrite the block arguing that a successful listing is
      authoritative: drop the storage-`LIST` read-after-write premise and the two capability names that no
      longer exist (`bunny-upload-endpoint`, `bunny-list-endpoint`), and state the v2 reason — the byte
      route records the row non-best-effort and answers `502` if it cannot, so a `2xx` implies a committed
      row, and with a single primary a later read sees it.
- [ ] 3.2 Confirm the edit is comment-only: `git diff` shows no change outside comment lines.

## 4. Verify and ship

- [ ] 4.1 `./gradlew build` green — nothing should change, which is the point.
- [ ] 4.2 Branch → PR with the `internal` label → `/ship`.

## 5. Follow-up worth recording, not doing here

- [ ] 5.1 Note for whoever picks up the reconciliation work: `database` no longer forbids a dedicated
      pass, and the uncovered case is now named in the contract — that is the citation to build on.
