## 1. Prerequisite

- [x] 1.1 Confirm `stop-uploading-excluded-photos` has landed. The comparison needs its row-level
      admission; without it the check reports the nightly sweep's residue as data loss.

## 2. The comparison, platform-free

- [x] 2.1 Add the read-only check in `:domain` `feature/upload` — takes the listing seam, the ledger, the
      policy and the marker; returns the two counts. No writes anywhere in it.
- [x] 2.2 Build the comparison set as policy-admitted ∩ believed-landed, reusing the same row-level
      admission the manifest projection and the enqueue stage use.
- [x] 2.3 Skip conditions: no configured event, and configured `eventId` != `joinedEventId` marker.
- [x] 2.4 Bound the listing fetch with a timeout, and treat a failure or timeout as no information —
      report nothing, count nothing.
- [x] 2.5 Report the fault direction at `Error` with counts, set sizes and the resolved upload mechanism;
      log the second direction at a lower severity so it rides as a breadcrumb.
- [x] 2.6 Unit tests for every branch, including the two that must stay silent (collected residue; a
      failed fetch).

## 3. Wire it into the foreground

- [x] 3.1 Add the device-listing seam to `AppPorts`, bound to the existing `HttpDeviceFilesSource` beside
      the app's other Ktor clients.
- [x] 3.2 Add the step to the `Foreground` flow beside `downloadController.reconcile(…)`, as a
      `compose/`-built effect lambda — the flow imports `model/` + `feature/` only.
- [x] 3.3 Add a floor so repeated foregrounds do not refetch — at most once per launch, or once per N
      minutes.
- [x] 3.4 `./gradlew architectureDiagrams` and commit — `architecture/flows/Foreground.md` changes, and a
      flow that cannot be transcribed fails generation.

## 4. Integration

- [x] 4.1 `:test:integration` over `:test:world`: the world's mini-edge already serves the v2 listing in
      identity terms — assert the fault is reported when a stored resource is removed behind the device's
      back.
- [x] 4.2 Assert **silence** for the sweep-shaped case: bytes gone, policy no longer admits. This is the
      test that earns the right to report at `Error`.
- [x] 4.3 Assert silence on a marker mismatch and on the world's offline `502` lever.
- [x] 4.4 Assert the ledger is byte-identical before and after a check that found a disagreement.

## 5. Specs

- [x] 5.1 Apply the `event-rejoin-reconciliation` delta.
- [x] 5.2 `npx --yes @fission-ai/openspec@1.5.0 validate --specs --strict`.

## 6. Verify and ship

- [x] 6.1 `./gradlew build` and `./gradlew compileIosMainKotlinMetadata` green.
- [ ] 6.2 Branch → PR with the `internal` label → `/ship`.

## 7. Read the answer

- [ ] 7.1 Dispatch a release-channel build (`gh workflow run ios.yml --ref <branch>`) so a DSN is baked
      and the report can actually reach Bugsink — dev and sideload builds carry none.
- [ ] 7.2 After it has been in internal TestFlight for a while, read the rate with `/bugsink`: does it
      fire at all, and does it cluster on iOS ≥26.1?
- [ ] 7.3 Record the answer wherever the repair gets proposed. If it never fires, that is the finding, and
      the repair should be dropped rather than built.
