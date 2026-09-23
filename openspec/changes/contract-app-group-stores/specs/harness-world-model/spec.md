## MODIFIED Requirements

### Requirement: Real-stack composition helpers

The world SHALL assemble its upload cycle through the **same shared composition the device tiers
call** — `uploadCore` (`:domain` `compose/`, spec `module-architecture` "One shared composition") over
the world's fakes — not through a world-local mirror of a composition root: the world supplies its
in-memory ports (the config ports as `:adapter:generic:fake`'s `InMemoryConfigStore` over the world's
config cell, with the `membershipUnreadable` lever a world property over the fake's readable cell — the
lever lives in the world, never in the fake; the fake `BackgroundTransfer`, the fake `UploadDiscovery`, the
`:adapter:generic:fake` ledger and manifest stores, the mini-edge HTTP seams) and `uploadCore` builds the real
`SyncEngine` + `EdgeUploadRequestProvider` + `UploadCycle` + `DeviceManifestProducer`
graph, exactly as it does for the device roots. The world composes **no** upload reconciler and **no**
joined-event marker, because `uploadCore` has neither: the upload ledger is loaded at a join and cleared at a
leave (capability `upload-lifecycle`), not reconciled inside a cycle. The app-side graph — download, status, membership, creation,
the command bundle — SHALL come from the composed `AppCore` (see "The world composes the app graph through
snapSyncApp"). Only the platform edges (`BackgroundTransfer`, `UploadDiscovery`, `DownloadTransport`,
`PhotoLibraryImporter`), the storage seams, and the HTTP client SHALL be fakes; everything above them SHALL
be the shipped production code.

#### Scenario: The composed upload path exercises the real cycle

- **WHEN** the world's `uploadCore`-assembled cycle is invoked
- **THEN** the real `SyncEngine`, `EdgeUploadRequestProvider`, and `UploadCycle` run, and only the job
  platform, library discovery, config and manifest stores, ledger backend, and HTTP client are fakes

#### Scenario: A wiring difference from production is impossible

- **WHEN** the world and a device tier each assemble an upload cycle
- **THEN** both call the same `uploadCore` function over different port implementations, so the world
  cannot carry gate, manifest, or policy wiring production lacks (or vice versa)

#### Scenario: The world's config double is the contracted fake

- **WHEN** the world composes its config ports
- **THEN** they are one `InMemoryConfigStore`, the fake held to the same config contract the real App-Group
  file store passes (capability `port-contracts`), so an unreadable membership answers the world as a real
  unreadable store would — reads unavailable, writes refused

#### Scenario: Production seams are not duplicated

- **WHEN** the world composes the manifest path
- **THEN** its `Enrollment` port is `:adapter:generic:app`'s `HttpEnrollment` over the injected mini-edge
  client — the world carries no copy of any production adapter (the step-10 death of the world's
  byte-identical `HttpEnrollment` closed the deletion ledger's last row)
