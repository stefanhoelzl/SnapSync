## 1. State (`:domain:model`, `:domain:presentation`)

- [x] 1.1 Add `@Serializable` `EventTiming` (`Upcoming(remaining)` / `Running(remaining)` / `Ended`) with a whole-unit `Remaining` (`Days` / `Hours` / `Minutes` / under a minute) and `SyncCounts` of two `DirectionCount`s (`Off` / `Progress(done, total)`) in `model/` (design D2, D4)
- [x] 1.2 `Layer.Joined`: add `timing`, `counts` (the range is read off `membership`, which it already carries); `ended` becomes a derived `timing == Ended`; `SyncHealth.NotStarted` drops `startsAt` (D6)
- [x] 1.3 `StatusContainerHost`: reduce `timing` from the config bounds and `nowTick` (floored, days → hours → minutes); reduce `counts` only under `InSync` / `Syncing`, `Off` only when the direction is switched off AND its total is 0 (D3)
- [x] 1.4 Presentation tests: each timing phase and unit boundary (≥1 day, <1 day, <1 hour, <1 minute, ended), the minute tick updating the countdown and stopping after the end, counts hidden in every non-sync health, a switched-off direction with work shows numbers, `In sync` never beside an incomplete direction

## 2. Components (`:ui:components`)

- [x] 2.1 `AppStatusLine`: the not-started variant reads "Sharing starts with the event" with no date; remove the ended marker and `endedDetail`; delete the now-dead start formatting
- [x] 2.2 Screen heading: the name clamps to two lines with an ellipsis; room for the joined statement and the dates line beneath it (D7)
- [x] 2.3 Component tests for the heading clamp and the range formatter (the not-started line is asserted through the screen in 3.6)

## 3. Joined screen (`:ui:screens`)

- [x] 3.1 Heading: green "You've joined this event" and the dates line — `12 – 14 Jul · ends in 2 days`, same-day `Today 18:00 – 23:00 · …` / `14 Jul 18:00 – 23:00 · …`, an end at 00:00 shown as the previous day, all via `CutoffFormatter` in the device zone (D5)
- [x] 3.2 Invite: eyebrow "Invite others", caption "Others join by scanning this with their camera"; update the rationale comment in `JoinedLayer` (D8)
- [x] 3.3 Counts line under the status line: `n/N shared · m/M received`, a complete side as its total, `Not sharing` / `Not receiving`; the waiting note beneath it when present (D2, D6)
- [x] 3.4 Closed event: name, joined statement, dates, status, counts, Leave only
- [x] 3.5 Keep the `ui` detekt tier within its ceilings by extracting the heading and counts composables (no ceiling raised without a stated forcing proof)
- [x] 3.6 `:ui:screens` UI tests: every state of the final mockup — syncing, in sync, sharing off, receiving off, before the start, last hours, same-day, ended + waiting, closed, no access, limited access, unverified, long name

## 4. Integration, harness, screenshots

- [x] 4.1 `:test:integration`: assert the joined `UiState`'s `timing` and `counts` in the existing status journeys (counts track shared / received; hidden without access and before the start)
- [x] 4.2 Review every joined state in the desktop world harness through its levers (the `ui-harness` skill), light and dark
- [x] 4.3 Dispatch `screenshots.yml` on the branch, eyeball the `in_sync` raws (light/dark), commit them; `create` and `joining` should come back identical

## 5. Verify

- [x] 5.1 `./gradlew build` green (including the `detekt*Tier` ceilings and `ShotsTest`)
- [x] 5.2 `./gradlew compileIosMainKotlinMetadata` green
- [ ] 5.3 On the SE2: the joined screen of a real event reads correctly while syncing and in sync
- [ ] 5.4 At sync/archive: rewrite `sync-status`'s Purpose paragraph ("a single status line — no photo counts") to match; run both archive gates from `openspec/config.yaml`
- [x] 5.5 `npx --yes @fission-ai/openspec@1.5.0 validate --specs --strict` and validate this change
