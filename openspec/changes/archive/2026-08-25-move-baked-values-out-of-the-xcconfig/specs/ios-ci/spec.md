## MODIFIED Requirements

### Requirement: Compile-time edge host default

The extension's baked upload host (`uploadBase` in the generated `Deployment.plist`) SHALL be
**derived from the resolved deployment** (capability `deployment-configuration`) — the single source of
the host, shared with the app's `LINK_ORIGIN`, the `applinks:` entitlement, the AASA the backend serves,
and the site's canonical URLs — so **every ref**, including the `main`/TestFlight build, targets the
deployment's device-facing host (safe because the device carries no storage credential and the endpoint
is the production backend). The iOS workflow SHALL **not** restate the host and SHALL provide **no**
mechanism to override the value directly, and none is available to anyone: the value reaches a generated
bundle RESOURCE, which no `xcodebuild` build-setting override can substitute into. This requirement is the **single owner** of the
compile-time upload-host contract; the TestFlight build inherits whatever host this shared archive step
bakes.

Previously the literal lived in `Config.xcconfig` and was checked by nothing — it was the one copy of the
domain that no guard inspected. Deriving it removes the literal rather than pinning it.

Targeting a different backend for a **development** build SHALL be an out-of-band operator action on the
ssh-mac build invocation (dev infrastructure; see the runbook in `CLAUDE.md`), never a CI input. Where the
target is a declared deployment it SHALL be expressed as **selecting** that deployment rather than as a
bare host string.

The previously admitted exception — a build-setting override for the local-rig tunnel, whose hostname
cloudflared mints **inside the running rig** and is random per session — is **withdrawn**, because the
mechanism it named no longer exists. An operator points a build at a local rig or a tunnel by writing
that host into the local deployment and **re-running the resolver**, which happens after cloudflared has
minted it. That is selecting a deployment rather than overriding a string, which this requirement already
preferred; the override was only ever the concession to a value arriving late, and re-resolving answers
that just as well. No CI input SHALL exist for it.

The one generated resource is copied into **both** bundles, so a single selection covers the app and the
background-upload extension together. The URL **scheme** SHALL be DERIVED from the host rather than
declared beside it: `http` for a loopback IP literal, `https` for every other host. Default ATS applies
and no `NSAllowsLocalNetworking` exception ships, so a plaintext host reached over the network fails
**silently** on device — while ATS exempts the loopback literal, which is what lets a simulator reach
`deno task dev:local` at all. Deriving it means a deployment cannot name a host and a scheme that
disagree, and a tunnel — not loopback — correctly stays HTTPS.

Decision record: `changes/archive/2026-08-25-add-deployment-resolver-and-boot-probe` (the host literal is removed
rather than pinned).

#### Scenario: Every build bakes the deployment's host
- **WHEN** the iOS workflow runs on any ref
- **THEN** the workflow sets no host override and the archive bakes the value generated from the
  resolved deployment into both bundles

#### Scenario: No host literal survives in the build settings
- **WHEN** the committed build settings are inspected
- **THEN** they contain no device-facing host literal; the value is supplied by the generated artifact

#### Scenario: TestFlight build targets the live endpoint
- **WHEN** the `ios-build` job runs on `refs/heads/main`
- **THEN** the uploaded TestFlight build's `BackgroundUploadURLBase` is the resolved deployment's HTTPS
  backend URL

#### Scenario: A development build retargets by selecting a deployment
- **WHEN** an operator builds against a declared backend on the ssh-mac loop
- **THEN** it is done by naming that deployment, and the host is derived from it rather than supplied as
  a bare string

#### Scenario: The local tunnel keeps a build-setting override, because its host cannot be declared
- **WHEN** an operator builds against the local rig behind a quick tunnel, whose hostname is minted after
  the resolver has already run and differs every session
- **THEN** the host is supplied as a build-setting override on that invocation
- **AND** no CI workflow exposes that override as an input

#### Scenario: A local rig is selected, not overridden

- **WHEN** an operator needs a build pointed at a local backend or a cloudflared tunnel
- **THEN** they set that host in the local deployment and re-run the resolver, because no build-setting
  override can reach a generated bundle resource

#### Scenario: The scheme follows the host

- **WHEN** the resolved host is a loopback IP literal
- **THEN** the baked base is `http`, and for every other host it is `https`
