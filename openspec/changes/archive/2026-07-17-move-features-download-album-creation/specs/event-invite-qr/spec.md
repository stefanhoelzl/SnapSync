# event-invite-qr — delta for move-features-download-album-creation

## MODIFIED Requirements

### Requirement: The container share action defaults to a no-op

`StatusContainerHost` SHALL accept the share action as an injected `share: (String) -> Unit` lambda
with a no-op default, mirroring the leave action. The composition root binds it to the platform share;
hosts and tests that do not exercise sharing (the desktop harness's real-share path, presentation
tests) SHALL construct unchanged, and a share in those contexts SHALL be inert. The presentation layer
SHALL gain no module dependency: the invite-link codec lives in `:domain`'s `model/` zone, reached through the `:domain` dependency presentation already has.

#### Scenario: A host without a real share action constructs and is inert
- **WHEN** `StatusContainerHost` is constructed without injecting a real share action
- **THEN** construction succeeds and invoking `onShareInvite()` performs no platform share

#### Scenario: Presentation gains no new module dependency
- **WHEN** the presentation module's dependencies are inspected after this change
- **THEN** it depends on no new module — the invite URL is derived via the already-present
  `model/` `EventLink` encoder and the share enters as a plain lambda

