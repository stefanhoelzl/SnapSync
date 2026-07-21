## 1. model/ — CreateEventPayload DTO + codec (tested)

- [x] 1.1 Add `@Serializable CreateEventPayload(name, startsAt: String? = null, autoJoin: Boolean = false, minPhotoDate: String? = null, direction: String? = null, saveToAlbum: Boolean? = null)` in `:domain` `model/` (new file, e.g. `CreateDirective.kt`), documenting each field as a dev/test key.
- [x] 1.2 Add `decodeCreateDirective(raw: String): CreateDecodeResult` (typed `Success`/`Failure`) using a strict `Json { ignoreUnknownKeys = false; isLenient = false }` over `base64url` (padding-optional decode), mirroring `decodeEventUrl`: reject non-base64url, non-UTF-8-JSON, missing `name`, empty `name`, unknown keys, and a `direction` outside `Direction.wire` tokens.
- [x] 1.3 Add `commonTest` coverage for `decodeCreateDirective`: valid minimal (`name` only), all optional keys, unknown-key rejection, malformed base64url, missing/empty `name`, bad `direction` token — runs on JVM + `iosSimulatorArm64`.

## 2. model/ — LaunchDirectives fields (tested)

- [x] 2.1 Add `createEvent: String?` and `leave: Boolean` to `LaunchDirectives`, its `NONE`, and `from(env)` (read `SNAPSYNC_CREATE_EVENT` as the raw value; `SNAPSYNC_LEAVE` as presence, i.e. `env("SNAPSYNC_LEAVE") != null`, matching `forceUrlSessionUpload`).
- [x] 2.2 Extend `LaunchDirectivesTest` (commonTest) for both new fields: present/absent, and `SNAPSYNC_LEAVE` presence-with-empty-value still `true`.

## 3. feature/creation — HeadlessCreate use-case (tested)

- [x] 3.1 Add `HeadlessCreate(client: EventCreation, log: Logger, now: () -> String)` in `:domain` `feature/creation` with `suspend fun run(payload: CreateEventPayload, forwardAutoJoinLink: (String) -> Unit)`: resolve `startsAt = payload.startsAt ?: now()`, call `client.create(payload.name.trim(), startsAt)`, branch on `CreateOutcome` — `Created(id)` → `if (payload.autoJoin) forwardAutoJoinLink(encodeEventUrl(EventLinkPayload(id, autoJoin = true, minPhotoDate = payload.minPhotoDate, direction = payload.direction, saveToAlbum = payload.saveToAlbum))) else log("created eventId=$id")`; `InvalidName`/`Transient` → log the reason.
- [x] 3.2 Add `HeadlessCreateTest` (commonTest) over a fake `EventCreation`: mint-only logs `created eventId=…` and forwards no link; autoJoin forwards a link that `decodeEventUrl` round-trips to the minted id + `autoJoin=true` + the supplied overrides; `startsAt` default uses the injected `now`; `InvalidName`/`Transient` forward no link.

## 4. compose/ — expose HeadlessCreate on AppCore

- [x] 4.1 Compose `HeadlessCreate` in `snapSyncApp` (wire the existing `EventCreation` client + a `now` from the port-backed clock the app already uses) and expose it as `AppCore.headlessCreate`.
- [x] 4.2 Confirm the `compose/` wiring smoke test still passes (graph assembles); no unit test of the wiring per the one-composition law.

## 5. :app:ios — parse directives + ordered membership application (wiring only)

- [x] 5.1 In `SnapSyncRoot`, decode `directives.createEvent` via `decodeCreateDirective` once per process (a `by lazy`), logging a rejection.
- [x] 5.2 Add a single ordered, sequential membership application (a `Shell` method, e.g. `applyLaunchEnvMembership()`), running in one coroutine: `leave` (await `commands.leave()` / the leave use-case) → `create` (await `app.attestation.ensureFresh()` then `app.headlessCreate.run(payload, ::onOpenUrl)`) → `event-link` (`directives.eventLink?.let { onOpenUrl(it) }`). Fold the existing `launchEnvEventLinkApplied` event-link step into this path (same `onOpenUrl` call, placed last).
- [x] 5.3 Implement the `Shell` method on `LiveShell` (runs the ordered application) and on `ForgeShell` (log-and-ignore, holding no route to `app`), keeping forge inertness structural.
- [x] 5.4 Update `MainViewController` to call the consolidated membership application once (replacing the standalone `applyLaunchEnvEventLink()` `LaunchedEffect`); leave the seed/policy-probe effects untouched.
- [x] 5.5 Verify `KotlinShellGuardTest` / `SwiftShellGuardTest` still pass (no new unpinned shell decision; the outcome branch lives in `HeadlessCreate`, not the shell).

## 6. Build, guards, and on-device verification

- [x] 6.1 `./gradlew build` (JVM tests + all targets) and `./gradlew compileIosMainKotlinMetadata` (iOS proxy) green; architecture guards pass (model-purity, ports→model, feature-blindness, shell guards).
- [x] 6.2 On device (SE2, over USB): `SNAPSYNC_CREATE_EVENT` mint-only logs `created eventId=<uuid>` in `debug.log` and joins nothing; an `autoJoin` create lands a live membership; a plain `SNAPSYNC_EVENT_LINK` launch still provisions once (event-link regression check); `SNAPSYNC_LEAVE` returns to the unjoined state; `SNAPSYNC_FORGE_STATE` + create/leave renders the forged frame and mints/leaves nothing.

## 7. Docs

- [x] 7.1 Root `CLAUDE.md` on-device runbook: document `SNAPSYNC_CREATE_EVENT` (payload keys, mint-only vs autoJoin, the `created eventId=<uuid>` oracle) and `SNAPSYNC_LEAVE`, plus the ordered `leave → create → event-link` precedence.
- [x] 7.2 Add the ⚠️ non-idempotency warning for `SNAPSYNC_CREATE_EVENT` (unset it after the mint — opposite of the "leave `SNAPSYNC_EVENT_LINK` set" per-build-loop advice), next to the existing event-link guidance.

## 8. OpenSpec

- [x] 8.1 `npx --yes @fission-ai/openspec@1.5.0 validate --specs --strict` passes for the change.
- [x] 8.2 Archive after merge via the standard flow (run the three archive gates: placeholder Purpose, delta completeness against touched modules, dead types).
