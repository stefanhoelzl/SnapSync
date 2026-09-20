## 1. Flip the seed

- [x] 1.1 `RangeForm.saveToAlbum` defaults `true` in
  `ui/presentation/src/commonMain/kotlin/app/snapsync/presentation/RangeForm.kt`, beside `shareOn`
  and `receiveOn`. Nothing else in that file moves — `reconfigureForm` keeps seeding from the
  persisted membership.
- [x] 1.2 Confirm by reading that no other production site re-seeds the album choice: `autoConfirm`'s
  `explicitSaveToAlbum ?: false` in `StatusContainerHost.kt` is the headless default and stays
  `false`.

## 2. Move the tests the default owns

- [x] 2.1 In `ui/screens/src/commonTest/.../JoinScreenTest.kt`, invert the default case: the album row
  is a checkbox **on** by default, and the both-feeds note is what an untouched gate renders.
- [x] 2.2 Keep coverage of the off-state note by seeding `RangeForm(saveToAlbum = false)` in a case
  that asserts "No album is created." — that string currently has exactly one assertion and it is
  the one being inverted.
- [x] 2.3 In the same file, fix "tapping the album opt-in reports the choice": a tap from the new
  default reports `false`. Either flip the expectation or seed the form off and keep asserting
  `true` — whichever reads as testing the callback rather than the seed.
- [x] 2.4 Add a presentation-level assertion that an untouched join gate commits `saveToAlbum = true`
  (spec scenario *An untouched gate commits the album*) — `JoinGateIntegrationTest`'s
  `confirmJoinAs` helper sets the value explicitly, so nothing currently covers the seed reaching
  `JoinEvent`.
- [x] 2.5 Add or extend a case for the spec scenario *The headless path does not inherit the surface
  default*: an `autoJoin` link with no override commits `saveToAlbum = false`.
- [x] 2.6 Leave `JoinGateIntegrationTest`'s `confirmJoinAs(saveToAlbum = …)` and
  `World.provision(saveToAlbum = false)` as they are — test-infra defaults that set the value
  explicitly, not product ones.

## 3. Verify

- [x] 3.1 `./gradlew build` — the canonical check, including the `:ui:screens:jvmTest` Compose tests
  and the `:test:architecture` gates.
- [x] 3.2 `./gradlew compileIosMainKotlinMetadata` — the Linux-runnable iOS proxy.

## 4. Marketing screenshots

- [x] 4.0 Unblock `screenshots.yml`, which could not build at all: `Config.xcconfig` #includes the
  generated, gitignored `Deployment.xcconfig`, and this workflow — alone among the iOS-building
  ones — never ran `scripts/resolve-deployment.py`. Broken on every dispatch since `3a93f5f4`
  (2026-08-25), two days after its last green run, so nothing had caught it. Pre-existing and
  independent of this change; folded in here because task 4.1 cannot run without it.
- [x] 4.1 `gh workflow run screenshots.yml --ref <branch>`, download `screenshots-raw`, and diff
  against the committed raws.
- [x] 4.2 Expect `create` to re-diff only in the 90×32 px wall-clock region. A `joining` diff IS this
  change (that raw is normally byte-identical); an `in_sync` diff means something else moved and
  needs explaining before it is committed.
- [x] 4.3 Eyeball every changed capture for a stray system notification (it hit 1 of 2 runs) and
  re-dispatch if one landed.
- [x] 4.4 NOTHING COMMITTED — `joining` and `in_sync` came back byte-identical in both appearances,
  because the album row sits below the fold: the 6.9" frame at scroll-top ends inside the Receive
  section, clipped by the pinned Join/Cancel. Verified by opening the capture, not inferred from the
  byte-compare. `create` moved in exactly two regions and nowhere else (diff bbox of the remainder is
  empty): the wall-clock date line it legitimately renders, and the status-bar clock/battery glyphs —
  capture noise in both cases, so committing them would be churn against an equally arbitrary older
  capture.

## 5. Land it

- [ ] 5.1 PR carries the `enhancement` changelog label — a guest sees a different join gate and gets
  an album they previously would not have.
- [ ] 5.2 `/ship`.

## 6. Sync (only on the user's word, after the tasks land)

- [ ] 6.1 Sync the `event-album` delta into `openspec/specs/event-album/spec.md`.
- [ ] 6.2 Hand-edit the Purpose, which a delta cannot reach: "An **opt-in**, per-membership album"
  now misstates the default. Reword to a declinable default-on album, keeping the rest of the
  Purpose (why it exists, who creates it, that identity survives leave) untouched.
- [ ] 6.3 Run the archive gates from `openspec/config.yaml` before archiving — placeholder Purposes,
  delta completeness against the touched modules (`:ui:presentation`, `:ui:screens`,
  `:test:integration`), and removed type declarations (this change removes none).
