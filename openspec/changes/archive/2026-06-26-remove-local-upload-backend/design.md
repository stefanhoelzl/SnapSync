## Context

The iOS upload path was built to support on-device testing against a **local** upload backend on the
LAN (the `scripts/local-s3.sh` MinIO rig). Reaching a private/LAN host over plaintext required two
relaxations that ship in **every** build, TestFlight included:

1. An `NSAllowsLocalNetworking` ATS exception in both the host-app and extension `Info.plist`.
2. A launch-time Local Network priming probe (`LocalNetworkPriming.kt`) so the app — not the
   prompt-less extension — surfaces and grants the iOS Local Network permission.

The upload host is a **compile-time** constant (`BackgroundUploadURLBase`, from the
`BACKGROUND_UPLOAD_URL_BASE` build setting); PhotoKit's background uploader cannot take a runtime
host. `Config.xcconfig` defaulted it to the inert `https://dummy.invalid` so a plain local Xcode
build uploaded nowhere, and CI overrode it (deployed host by default, or a LAN host via the
`workflow_dispatch upload_host` input).

A real HTTPS backend is now deployed and device-facing (`https://snapsync.stefanhoelzl.deno.net`,
streaming proxy → bunny native Storage). The local backend is obsolete, and the non-secure allowance
it justified is unnecessary attack surface.

## Goals / Non-Goals

**Goals:**
- Forbid non-secure (plaintext) upload connections: remove the ATS exception so default HTTPS-only
  ATS applies to the app and extension.
- Remove the obsolete local-backend path: the MinIO rig, the priming probe, and the http-baking CI
  input.
- Keep the ability to bake an alternate **HTTPS** host into a dev IPA (e.g. a future staging
  backend) via `workflow_dispatch`, but reject non-https values.
- Single-source the deployed host literal in `Config.xcconfig`.

**Non-Goals:**
- No change to the public HTTPS upload behavior or the `backend/` Deno project (incl. its local
  `deno run` convenience — that is backend-dev tooling, not the device's http path).
- No runtime URL-scheme guard in Kotlin (`EdgeUploadRequestProvider` is unchanged).
- No change to the QR/deeplink config payload (already host-less; carries only `eventId`).

## Decisions

**1. Enforcement is ATS-only — no Kotlin scheme guard.**
Removing `NSAllowsLocalNetworking` makes iOS reject any plaintext PUT at the platform level. The host
is a compile-time constant controlled by `Config.xcconfig` + the CI https guard, so a non-https host
can only arise from a deliberate misconfiguration of a tracked file. Passing an HTTPS host is the
developer's responsibility.
- *Alternative considered:* a `require(host.startsWith("https://"))` guard in
  `EdgeUploadRequestProvider`, pinned by a `commonTest`. Rejected: it adds a runtime failure path
  for a condition that cannot occur in shipped builds, and ATS already enforces the contract.

**2. Keep `upload_host`, constrain to HTTPS; single-source the default.**
The `workflow_dispatch upload_host` input stays for baking an alternate HTTPS host, but the workflow
fails fast if it is set and not `https://`. When the input is empty, CI does **not** override the
build setting — the archive uses the `Config.xcconfig` default — so the deployed host literal lives
in exactly one file.
- *Alternative considered:* remove the input entirely (always Deno Deploy). Rejected: keeping an
  https-only override preserves a staging path at negligible cost.
- *Alternative considered:* keep CI restating the literal (`inputs.upload_host || 'https://…'`).
  Rejected: duplicates the host across `ios.yml` and `Config.xcconfig`.

**3. `Config.xcconfig` defaults to the real deployed host, not the inert dummy.**
A plain local Xcode build now targets the deployed backend. For a single-user personal app where the
developer is the user, "dev build = real backup" is the desired behavior, and it removes the dummy
host's special-casing. (See the risk below — this trades away an upload interlock.)

**4. Delete the priming probe outright.**
`primeLocalNetwork(...)` only granted a Local Network permission relevant to a private/LAN host.
Against the public HTTPS endpoint it is a no-op, so the function and its `SnapSyncRoot` call site are
removed. `:app:ios` is wiring-only and untested, so no test is affected.

**5. Consolidate the upload-host contract into `ios-ci`.**
The compile-time upload-host default+override was specified **twice** — in `ios-ci` ("Compile-time
edge host default and override") and in `ios-sideload-delivery` ("Optional compile-time upload host
via workflow_dispatch"). Since both this change touches both specs anyway, the duplicate is removed:
`ios-ci` becomes the single owner (the host is baked in the shared archive step — the merge gate —
and the default applies to the TestFlight build on `main` too, which is not a sideload concern), and
the sideload requirement is dropped. The sideload IPA inherits whatever host the shared archive bakes.
- *Alternative considered:* keep both and cross-reference. Rejected: two normative requirements for
  one contract drift apart (they already had — the sideload copy still said `dummy.invalid`).
- *Alternative considered:* make `ios-sideload-delivery` the owner. Rejected: the host applies to all
  channels, so the workflow capability (`ios-ci`) is the broader, correct home.

## Risks / Trade-offs

- **Local debug builds can write to prod** → A local Xcode "Run" onto a phone that already holds a
  scanned `eventId` will back that library up to the production storage zone (uploads still require a
  config payload + photo permission + the on-device extension). Accepted by design for this
  single-user app; the previous `dummy.invalid` interlock is intentionally gone.
- **Duplicated host literal if a future non-CI build path emerges** → The single source is
  `Config.xcconfig`; any new packaging path must read the build setting rather than re-stating the
  URL. Mitigated by keeping CI's empty-input case a pass-through.
- **An operator who relied on the local MinIO loop loses it** → On-device upload verification now
  targets the deployed backend's storage zone (no local rig). Mitigated by a one-line pointer left in
  `CLAUDE.md`; the `backend/` project's local `deno run` remains for backend-only iteration.
- **Spec/code drift on the `upload_host` default** → The current `ios.yml` already deviates from the
  archived spec's `dummy.invalid` default; this change re-aligns the spec to the
  fall-through-to-xcconfig behavior, closing the drift.
