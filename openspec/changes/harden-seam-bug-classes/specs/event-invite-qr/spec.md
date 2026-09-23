## MODIFIED Requirements

### Requirement: Sharing the invite is fire-and-forget

The presentation layer SHALL expose an `onShareInvite()` intent that hands the invite deeplink string
to the platform share. The share action SHALL reach `StatusContainerHost` as the `share: (String) -> Unit`
member of the required `UserCommands` bundle (not a named seam type, and not a constructor parameter of its
own), and `onShareInvite()` SHALL invoke it with the current invite URL when one exists. The share SHALL be
fire-and-forget: the screen SHALL NOT observe or react to the share's completion, cancellation, or
dismissal, and `UiState` SHALL be unaffected by sharing (the status projection stays correct while and after
any platform share UI is presented).

#### Scenario: Sharing hands the deeplink to the platform
- **WHEN** `onShareInvite()` is invoked with an event configured
- **THEN** the bundle's `share` command is called with the invite deeplink string

#### Scenario: Sharing does not change the status projection
- **WHEN** the platform share UI is presented over the status screen and then dismissed (shared or
  cancelled)
- **THEN** the status screen reflects the same live `UiState` it would have shown regardless, with no
  share-driven state to restore

## REMOVED Requirements

### Requirement: The container share action defaults to a no-op
**Reason**: As for the leave action (capability `leave-event`): the container's commands arrive as the one required
`UserCommands` bundle, with no defaults in production, so "inert by default" is no longer a state a host can be in.
**Migration**: A host that does not exercise sharing passes a bundle whose `share` does nothing. The presentation
layer still gains no module dependency: the invite-link codec is `model/`'s `EventLink` encoder.
