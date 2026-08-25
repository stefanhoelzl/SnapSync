## MODIFIED Requirements

### Requirement: Compile-time edge host default

The extension's `BackgroundUploadURLBase` (build setting `BACKGROUND_UPLOAD_URL_BASE`) SHALL be
**derived from the resolved deployment** (capability `deployment-configuration`) — the single source of
the host, shared with the app's `LINK_ORIGIN`, the `applinks:` entitlement, the AASA the backend serves,
and the site's canonical URLs — so **every ref**, including the `main`/TestFlight build, targets the
deployment's device-facing host (safe because the device carries no storage credential and the endpoint
is the production backend). The iOS workflow SHALL **not** restate the host and SHALL provide **no**
mechanism to override the value directly: it SHALL omit any `BACKGROUND_UPLOAD_URL_BASE` override on
every ref and let the generated value flow through. This requirement is the **single owner** of the
compile-time upload-host contract; the TestFlight build inherits whatever host this shared archive step
bakes.

Previously the literal lived in `Config.xcconfig` and was checked by nothing — it was the one copy of the
domain that no guard inspected. Deriving it removes the literal rather than pinning it.

Targeting a different backend for a **development** build SHALL be an out-of-band operator action on the
ssh-mac build invocation (dev infrastructure; see the runbook in `CLAUDE.md`), never a CI input. Where the
target is a declared deployment it SHALL be expressed as **selecting** that deployment rather than as a
bare host string.

There is exactly ONE admitted exception, and it is forced rather than chosen: the local-rig tunnel. A
quick tunnel's hostname is minted by cloudflared **inside the running rig**, after the resolver has run,
and is random per session — no declared file can hold a value that does not yet exist. That case SHALL
therefore remain a build-setting override on the operator's own invocation. It SHALL NOT be available as
a CI input, and no other case SHALL use it.

The one generated setting feeds **both** targets' `Info.plist`, so a single selection covers the app and
the background-upload extension together. The resulting host SHALL remain **HTTPS**: default ATS forbids
plaintext and no `NSAllowsLocalNetworking` exception ships, so a baked `http://` host would fail
silently on device.

#### Scenario: Every build bakes the deployment's host
- **WHEN** the iOS workflow runs on any ref
- **THEN** the workflow sets no `BACKGROUND_UPLOAD_URL_BASE` override and the archive bakes the value
  generated from the resolved deployment

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
