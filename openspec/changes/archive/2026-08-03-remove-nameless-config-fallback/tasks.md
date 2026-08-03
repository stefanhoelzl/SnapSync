## 1. The type

- [x] 1.1 Remove the `= ""` default from `EventConfig.name` (`domain/.../model/EventConfig.kt`), leaving `val name: String`.
- [x] 1.2 Rewrite the `name` paragraph of the `EventConfig` KDoc: the reason is **compile-time totality** at every construction site, not legacy handling; state that requiring the key does **not** require a non-blank value, and point at `HttpEventDirectory` as where that invariant lives. Keep it short — the full argument belongs in the decision record, cited as `Decision record: changes/archive/<id>`.
- [x] 1.3 Fix the sentence later in the same KDoc that calls `minPhotoDate`'s no-default "unlike [name]/[direction]/[saveToAlbum]" — `name` is no longer in that list.

## 2. The compensations

- [x] 2.1 Delete `MembershipRefresh.fetchNeed` and the `TitleNeed` enum.
- [x] 2.2 Delete `Provision`'s step-6 `when (membershipRefresh.fetchNeed(...))` block and the `membershipRefresh` / `fetchEventDetails` constructor parameters, along with the now-unused imports. Update the class KDoc's step-6 description (it currently names the fetch and cites `fetchNeed`).
- [x] 2.3 Drop the two arguments from the `Provision(...)` construction in `compose/SnapSyncApp.kt`. Leave `membershipRefresh` and `fetchEventDetails` in place — `Foreground` still uses them.
- [x] 2.4 Re-document `MembershipRefresh`'s name refresh (the `if (current.name != fetched.name)` line and the class KDoc) as **convergence on the served name**, not the scan-path nameless fill. Behavior unchanged.
- [x] 2.5 Drop `|| name.isEmpty()` from `AlbumCoordinator.ensureAlbum`'s leading guard and update its KDoc, which currently explains the clause.

## 3. The boundary

- [x] 3.1 In `HttpEventDirectory`, reject a **blank** name as `EventDetails.Failed` alongside the existing missing-name case.
- [x] 3.2 Comment that check as the **sole** guard: the config type requires the `name` key, not a non-blank value, and the album guard that used to catch the consequence is gone.
- [x] 3.3 Update the `EventDirectory` port KDoc, which currently says only that a `200` *lacking* a name maps to `Failed`.

## 4. Diagnostics

- [x] 4.1 In `SnapSyncRoot`, change the config log line's `named=${cfg.name.isNotEmpty()}` (now a constant) to `name=${cfg.name}`.

## 5. Tests

- [x] 5.1 `EventConfigTest`: invert the legacy-decode test — a config JSON without a `name` key fails to decode. Name it for what it pins, not for "legacy".
- [x] 5.2 `EventConfigTest`: add the weakness pin — `{"name":""}` **decodes successfully** — with a comment sending the reader to the boundary guard.
- [x] 5.3 `MembershipRefreshTest`: delete the `fetchNeed` test; change the two `name = ""` fixtures to a stale real name so the name-refresh assertion keeps its meaning.
- [x] 5.4 `AlbumCoordinatorTest`: delete the empty-name no-op test.
- [x] 5.5 `HttpEventDirectoryTest`: add a blank-name case (empty and whitespace-only) yielding `Failed`, beside the existing missing-name case.
- [x] 5.6 `ReconfigureIntegrationTest`: update the comment that explains `ensureAlbum` is a no-op for a nameless membership.
- [x] 5.7 Grep the tree for any other `name = ""` fixture or assertion that now encodes an impossible state.

- [x] 5.8 `World.provision` took a nullable name and coerced `null` to `""` — a state the real stack can no longer hold. Give it a `DEFAULT_EVENT_NAME` default instead (`BackendStore.registerEvent`'s nullable name stays: the *backend* may serve a nameless response, a *membership* may not). Found during 5.7; not in the original plan.

## 6. Specs and generated artifacts

- [x] 6.1 **Done at `/opsx:archive`.** This repo syncs specs at archive time in its own `docs(openspec): sync + archive …` commit (precedent `17d6a9d4`, `89a92b09`), not during apply. All five MODIFIED requirements applied and diffed: 43 removed non-blank lines, every one intended.
- [x] 6.2 Note in the change record that the `event-link` deltas also correct pre-existing drift: the `EventConfig` shape in "Config source and store seams" (nullable `minPhotoDate`, four missing fields, a stale field enumeration in the `save` idempotency clause) and the payload field list in "iOS file-backed config store". Recorded in `design.md` Risks and `proposal.md` Capabilities.
- [x] 6.3 Run `./gradlew architectureDiagrams` and commit the regenerated `architecture/flows/Provision.md` (the `alt` block goes) and `architecture/features.md` (`TitleNeed` drops out of the membership feature's type list).

## 7. Verification

- [x] 7.1 `./gradlew build` — all JVM tests, the architecture guards, `detektAppShell`, and the diagrams check.
- [x] 7.2 `./gradlew compileIosMainKotlinMetadata` — the Linux-runnable proxy for the iOS source sets.
- [x] 7.3 `npx --yes @fission-ai/openspec@1.5.0 validate --specs --strict`.
- [x] 7.4 Drive the world harness headlessly (`./gradlew :test:harness-driver:driveWorld`) through a real join and confirm the status screen reaches its joined state with the event's title — the composed `AppCore` exercising the real `Provision` flow without its deleted branch.
- [ ] 7.5 Confirm the PR carries the `internal` changelog label (no customer-visible behavior changes).
