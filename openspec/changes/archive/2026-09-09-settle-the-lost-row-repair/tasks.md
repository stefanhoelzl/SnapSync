## 1. Confirm the facts before editing the contract

- [x] 1.1 Re-read `api/src/db.ts` `publishStatements` — confirm the `if (!opts.legacy) continue;` guard is
      what skips the `resources` upsert, and that only the v1 route passes `{ legacy: true }`.
- [x] 1.2 Re-read `api-endpoints` lines ~129–136 and ~280–290 — confirm it already states the v1 repair and
      the v2 absence, so this change corrects `database` alone and introduces no third account.
- [x] 1.3 Confirm with the operator that the deployment is a single primary with no read replica, and
      record where that was confirmed. The replica edit rests on it.

## 2. Correct the database spec

- [x] 2.1 Apply the *The database holds only rebuildable state* delta: separate reconstructibility from
      automatic repair, state the v2 upload-record path, and name the uncovered case plainly.
- [x] 2.2 Remove "There SHALL be no dedicated reconciliation pass", replacing it with the narrower rule
      that no repair may be assumed as a side effect of an unrelated write.
- [x] 2.3 Apply the *Replica staleness* delta: state the single-primary deployment and the analogy the
      caution came from, keep the sweep's primary-transaction discipline, and make the guard explicitly
      conditional on a topology change.
- [x] 2.4 `npx --yes @fission-ai/openspec@1.5.0 validate --specs --strict`.

## 3. Correct the stale justifications outside the spec

- [x] 3.1 In `domain/feature/upload/Reconciler.kt`, rewrite the block arguing that a successful listing is
      authoritative: drop the storage-`LIST` read-after-write premise and the two capability names that no
      longer exist (`bunny-upload-endpoint`, `bunny-list-endpoint`), and state the v2 reason — the byte
      route records the row non-best-effort and answers `502` if it cannot, so a `2xx` implies a committed
      row, and with a single primary a later read sees it.
- [x] 3.2 Confirm the edit is comment-only: `git diff` shows no change outside comment lines.
- [x] 3.3 In `api/README.md`, correct the *Deployment invariant* block: it repeats the analogy-from-storage
      framing for the relational store. State the single-primary deployment, keep the sweep's
      interactive-transaction discipline, and make the guard conditional on the topology gaining replicas.
      (Added while applying: the backend's doc of record would otherwise contradict the corrected spec.
      Operator approved the scope widening.)

## 4. Verify and ship

- [x] 4.1 `./gradlew build` green — nothing should change, which is the point.
- [ ] 4.2 Branch → PR with the `internal` label → `/ship`.

## 5. Follow-up worth recording, not doing here

- [x] 5.1 Note for whoever picks up the reconciliation work: `database` no longer forbids a dedicated
      pass, and the uncovered case is now named in the contract — that is the citation to build on.
