## MODIFIED Requirements

### Requirement: Left-pane status emerges from the real stack

The left pane's `StatusScreen` SHALL be driven by the world's **real** platform-agnostic sources, not forged
cells. Those sources are:
- the real `LedgerBackedSyncStatusSource` (`world.syncStatusSource(scope)`);
- the real store-backed download status (`StoreDownloadStatusSource` over the world's download store);
- the real `CreateEvent` (an `EventCreator`);
- the world's creation-status and permission sources;
- the world's config source.

The status host rendering them SHALL be the world's host, built by the shared host composition the iOS shell
calls (`module-architecture`, "One shared composition"). It SHALL NOT be assembled in the harness.

No forged `SyncStatus`/`DownloadProgress` cell SHALL exist in the full-stack harness — every count shown on
the phone frame SHALL be computed by the real projection over real world state.

#### Scenario: A completed upload moves the phone-frame counts

- **WHEN** an own asset is added, its upload job created and completed, and the extension is invoked
- **THEN** the object is present in the world's backend store and the left pane's completed count
  advances toward the total — because the real projection recomputed, not because a count was forged

#### Scenario: The status counts cannot be typed in

- **WHEN** the harness code is inspected
- **THEN** the left pane's sync and download sources are the world's real `LedgerBackedSyncStatusSource`
  and `StoreDownloadStatusSource`, with no writable `SyncStatus`/`DownloadProgress` override cell

#### Scenario: The harness builds no host of its own

- **WHEN** the harness mounts the left pane
- **THEN** it renders the world's status host from the shared host composition, and harness code constructs
  no `StatusContainerHost`

## ADDED Requirements

### Requirement: The harness can mirror a remote host

`:app:desktop:run` SHALL be able to attach to a remote host of the control channel, as a typed-client client,
instead of composing a world. The remote host may be the JVM host, a simulator app or a phone. In that mode:
- **The left pane** SHALL render the real `StatusScreen` from the host's wire `UiState`, re-read by polling.
- **Taps** SHALL be sent as the host's `/user` intents.
- **A tap with no `/user` intent** SHALL be inert and logged. It SHALL never be emulated locally, because the
  surface it opens lives in the remote container.
- **The right pane** SHALL show the host's advertisement (`GET /device`) and its latest state, and SHALL pull
  no world lever of its own.

The harness SHALL NOT arrange how a remote host is reached. Hosts bind loopback only, so an operator forwards
a port first.

#### Scenario: Mirroring a simulator app

- **WHEN** the harness is launched attached to a simulator app's forwarded rig port
- **THEN** the left pane shows that app's current status screen, and a leave tap reaches it as the `/user`
  leave intent

#### Scenario: A surface tap in the mirror

- **WHEN** the operator taps a control whose effect is to open a surface in the remote container
- **THEN** nothing is sent and nothing opens locally, and the harness logs that the tap has no intent
