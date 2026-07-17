# harness-world-model — delta for establish-shared-composition

## MODIFIED Requirements

### Requirement: Real-stack composition helpers

The world SHALL assemble its upload cycle through the **same shared composition the device tiers
call** — `uploadCore` (`:domain` `compose/`, spec `module-architecture` "One shared composition") over
the world's fakes — not through a world-local mirror of a composition root: the world supplies its
in-memory ports (`ConfigReader` over the config cell and the `membershipUnreadable` lever, the fake
`BackgroundTransfer`, the in-memory ledger/discovery/manifest/marker stores, the mini-edge HTTP seams)
and `uploadCore` builds the real `SyncEngine` + `EdgeUploadRequestProvider` + `UploadCycle` +
`ExtensionReconciler` + `DeviceManifestProducer` graph, exactly as it does for the device roots. The
world SHALL additionally provide the download path (real `DownloadController` over
`HttpEventUnionSource`, and the real `QueuedPhotoDownloadJobs` over a fake `DownloadTransport`), the
ledger-backed status path (real `OwnDeviceGalleryStatusSource` + `LedgerBackedSyncStatusSource` over the
world's real ledger), and the create-event path (real `CreateEvent` over `HttpEventCreation`).
Only the platform edges (`BackgroundTransfer`, `DownloadTransport`, `PhotoLibraryImporter`), the storage
seams, and the HTTP client SHALL be fakes; everything above them SHALL be the shipped production code.

#### Scenario: The composed upload path exercises the real cycle

- **WHEN** the world's `uploadCore`-assembled cycle is invoked
- **THEN** the real `SyncEngine`, `EdgeUploadRequestProvider`, and `UploadCycle` run, and only the job
  platform, discovery store, ledger backend, and HTTP client are fakes

#### Scenario: A wiring difference from production is impossible

- **WHEN** the world and a device tier each assemble an upload cycle
- **THEN** both call the same `uploadCore` function over different port implementations, so the world
  cannot carry gate, reconcile, manifest, or policy wiring production lacks (or vice versa)

#### Scenario: Production seams are not modified

- **WHEN** the world composes the manifest path
- **THEN** it uses a common `HttpEnrollment` living in `:test:world` as its `Enrollment` port (dying
  into the shared adapter at migration step 10), leaving production's `:adapter:generic`
  `HttpEnrollment` and the `device-manifest` seam home unchanged
