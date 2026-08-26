## 1. The seam

- [x] 1.1 Add `adapter/ios/app-only/src/iosMain/kotlin/app/snapsync/ios/urlsession/TransferSessions.kt`:
      `internal expect fun transferSessionConfiguration(identifier: String): NSURLSessionConfiguration` and
      `expect val transferSessionBinding: String`. The KDoc carries the whole argument — the target rule, the
      `nsurlsessiond` refusal with the daemon's quoted line, what the default binding cannot evidence, the
      predicted receipt expiry, and the ⏰ expiry trigger — in the register of
      `:adapter:ios:ext-safe`'s `DeviceIdStores.kt`, which is the model to read first.
- [x] 1.2 Add `adapter/ios/app-only/src/iosArm64Main/kotlin/app/snapsync/ios/urlsession/TransferSessions.kt`:
      `backgroundSessionConfigurationWithIdentifier(identifier)` with `discretionary = false`,
      `allowsCellularAccess = true`, `sessionSendsLaunchEvents = true` — each measured to be that
      configuration's own default (design.md variant 5), so this is explicit, not a change. Binding
      `"background"`.
- [x] 1.3 Add `adapter/ios/app-only/src/iosSimulatorArm64Main/kotlin/app/snapsync/ios/urlsession/TransferSessions.kt`:
      `defaultSessionConfiguration()` with `allowsCellularAccess = true` and **nothing background-only**.
      Binding `"default"`. Log once here, at construction, that this target's sessions never report
      `didFinishEventsForBackgroundURLSession`, so a `handleEventsForBackgroundURLSession` wake holds its
      receipt to the deadline and expires — a host limit, not a fault.
- [x] 1.4 Confirm no `build.gradle.kts` edit is needed: `iosArm64Main` and `iosSimulatorArm64Main` are
      default target source sets (`:adapter:ios:ext-safe` declares neither and has both).

## 2. The call sites

- [x] 2.1 `IosDownloadTransport.kt:57-65` — replace the `run { }` configuration block with
      `transferSessionConfiguration(DOWNLOAD_SESSION_ID)`; the three property assignments move to 1.2. Keep
      the surrounding KDoc's "built eagerly" reasoning, and point its background-session claims at the seam.
- [x] 2.2 `IosUrlSessionUploadPlatform.kt:159-160` — replace the configuration construction with
      `transferSessionConfiguration(sessionId)`. Its long session comment is the artefact that misled twice;
      rewrite it to point at the seam rather than restating the measurement a third time.
- [x] 2.3 Verify no other construction site exists: `grep -rn 'SessionConfiguration' --include=*.kt` should
      show the seam's actuals and nothing else.

## 3. Reporting the binding

- [x] 3.1 `:test:rig` `Boot.kt` — read `transferSessionBinding` and pass it into `RigHooks`.
- [x] 3.2 `RigHooks.buildFacts()` — add `transferBinding`, beside `uploadTier` and `uploadBase`.
- [x] 3.3 `RigServer.kt:300-305` — the `Receipted` response gains `transferBinding`, and its `note` extends
      to say that under `default` the run exercised adopt and session-identifier routing only, that the OS
      delivered and relaunched nothing, and that an expiry is the host's inability to signal drain.
- [x] 3.4 Check `RigControlChannelTest`'s pins still hold (it pins `Boot.kt` and `IosRigBuilders.kt` by path)
      and extend them if the trigger-coverage derivation notices the new field.

## 4. The two gates

- [x] 4.1 `:test:architecture` — new `TransferSessionBindingTest`: the `iosArm64Main` actual names
      `backgroundSessionConfigurationWithIdentifier`, the `iosSimulatorArm64Main` actual does not, and a
      missing actual fails rather than passing vacuously. Model it on `KeychainContainmentTest`.
- [x] 4.2 `:adapter:ios:app-only` — a test asserting the seam yields a nil-identifier configuration on
      `iosSimulatorArm64` and that `transferSessionBinding` reads `"default"`. Note `iosTest` compiles for
      both targets, so this belongs where it only runs on the simulator.
- [x] 4.3 Add the laws-digest / `LawsDigestTest` check only if the new `architecture-guards` requirements
      touch a law line — they should not; confirm rather than assume.

## 5. Verify

- [x] 5.1 `./gradlew compileIosMainKotlinMetadata` — the Linux-runnable proxy, catches `iosMain` breakage.
      Note it compiles the **common** iOS source set, so it will NOT catch a broken `actual`; 5.3 does.
- [x] 5.2 `./gradlew build` — the canonical check, including both new gates.
- [x] 5.3 On ssh-mac: `./gradlew :adapter:ios:app-only:iosSimulatorArm64Test` and
      `:test:architecture:test`. This is the only run that compiles and executes the simulator actual.
- [x] 5.4 On ssh-mac, end to end: build + `scripts/sim-sign` + install, point at `deno task dev:local`, and
      confirm a real download lands and a real upload leaves — the thing this change exists for. Load the
      `ios-simulator` skill; it needs no device lease.
      **BOTH DIRECTIONS PROVEN, TWO MEMBERS, ONE EVENT (2026-08-26, fresh runner, macOS 26.5.2 / Xcode
      26.6, iOS 26.5).** Device A seeded with three 4.8 MP photos, device B seeded with none; both
      `transferBinding=default`, tier pinned `url_session`, local backend.
      · **UPLOAD** — A: ledger `completed=3 pending=0`, three PNG objects + device manifest in
        `api/.localstore`. (Reproduced from the 2026-08-25 run on a different runner.)
      · **DOWNLOAD** — B joined `DownloadOnly` off A's invite link and pulled all three:
        `transfer finished: status=200 expected=1649788 received=1649788 → stage` (and 1649800, 1650026 —
        received == expected exactly, matching the three source files), then three
        `imported foreign asset <A-asset-id> as <new-local-id>` lines. Zero `NSURLErrorDomain/-1` anywhere
        in B's log. Before this change every one of those transfers was `-1` with no bytes moved.
      ⚠️ **Verification lesson for the next scenario author:** `/device/state`'s `download` view is a
      *progress* read-model and returns to `{downloaded:0,total:0}` once the queue drains. The transfers
      completed ~1 s after the trigger, so a poll 25 s later reads 0/0 and looks like a failure. Assert on
      the device log's `transfer finished` / `imported foreign asset` lines, or on the gallery, not on that
      counter. (`/device/gallery` census is also not clean proof by itself — a simulator ships with stock
      photos, so B's `total` was 9, not 3.)
- [x] 5.5 Confirm `./gradlew architectureDiagrams` produces no diff (no port, module edge, or composition
      seam moves), so the required `diagrams` check stays green.

## 6. Docs and spec sync

- [x] 6.1 `.claude/skills/ios-simulator/SKILL.md` — rewrite the "No downloads, and no background
      `URLSession` at all" bullet. The cause, the six ruled-out fixes and the `log stream` command stay; the
      consequence changes to: transfers work over the target's default binding, and here is what a run there
      does not evidence.
- [x] 6.2 At sync time, edit `ios-url-session-upload`'s **Purpose** by hand (lines ~14-18) — it states the
      transport "itself is not [simulator-testable]: a background `URLSession` transfers nothing on a
      simulator". The delta specs do not carry Purpose text; this is the passage the CLI will not move.
- [x] 6.3 Add this change to the Decision-record list in `ios-url-session-upload`'s and `photo-download`'s
      Purpose.
- [x] 6.4 Run the archive gates before reporting done — placeholder Purpose, delta completeness (map every
      touched module to a capability or a recorded reason), dead types. They are in `openspec/config.yaml`'s
      context block, not in `.claude/`.
      **RESULTS (2026-08-26).**
      · Gate 1 placeholder Purpose — clear across the whole tree.
      · Gate 2 delta completeness — modules touched: `:adapter:ios:app-only` → `ios-url-session-upload`
        + `photo-download` (both delta'd); `:test:architecture` → `architecture-guards` (delta'd);
        `:test:rig` → **no delta, and none is owed**: it is non-gating dev infrastructure with no spec by
        construction (`changes/archive/2026-08-09-add-rig-control-channel`), and its surfaces here are
        projections of facts specified elsewhere; `.claude/skills/ios-simulator/SKILL.md` → **no delta**:
        runbook prose is owned by no capability, and `architecture-guards`' "Every runbook pointer
        resolves to a skill that exists" is untouched (no pointer added, removed or renamed).
        `upload-lifecycle` carries a delta with no module of its own — a spec-only correction.
      · Gate 3 dead types — this change removes no type declaration; clear.
      ⚠️ **Scope the gates to `HEAD`, not `origin/main`.** This branch was 0 ahead / 11 behind, so an
      `origin/main` diff reports the *inverse* of main's newer commits: it named `api`, `deployments`,
      `scripts`, `test/world` as "touched" and `FileEntryDto`/`MemberState`/`Membership` as "removed
      types", none of which this change goes near.
      · **A real collision the staleness hid**, found by diffing the restated requirement against main
        rather than against the base: main renamed the capability `bunny-list-endpoint` → `api-endpoints`
        *inside* `photo-download`'s "Background resource download to durable staging" — the requirement
        this change restates whole. The stale restatement would have silently reverted that rename and
        resurrected a deleted capability name. Both the delta and the synced spec now carry
        `api-endpoints`. **Re-diff the restated requirement against `origin/main` before shipping** —
        the branch is still 11 behind and needs a rebase.

## 7. Ship

- [ ] 7.1 Branch → PR with the `internal` changelog label → `/ship`.
