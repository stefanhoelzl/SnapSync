## MODIFIED Requirements

### Requirement: Per-version tier selection

Upload-mechanism selection SHALL be a pure **resolution**, not a branch in the app composition root, and
the OS fact `backgroundUploadSupported()`
(`NSProcessInfo.isOperatingSystemAtLeastVersion(major=26, minor=1, patch=0)`) SHALL be one of its inputs
(`upload-lifecycle`, "The upload mechanism is resolved, never selected"). Where it is `false` the
app-driven mechanism (the `IosUrlSessionUploadPlatform`, the `BackgroundUploadPump`, and the
`IosBackgroundScheduler`) is the only kind resolution may yield — it is the only mechanism that exists
there, and the OS-driven registration selector does not exist to be called. Where it is `true`,
resolution SHALL yield the PhotoKit kind under `GRANTED` and the app-driven kind under `LIMITED` (the OS
never invokes the extension under a partial grant — capability `ios-photokit-upload`), and the app-driven
kind resolved on such an OS SHALL relinquish any surviving OS-driven registration before it pumps.

The two mechanisms SHALL be mutually exclusive within one running process, and that exclusion SHALL be
**structural**: the orchestrator holds one producer reference, so two cannot be started
(`upload-lifecycle`, "Exactly one producer started per process").

#### Scenario: Version gate selects the app-driven mechanism below 26.1
- **WHEN** `backgroundUploadSupported()` returns false
- **THEN** resolution yields only the app-driven kind and `setUploadJobExtensionEnabled` is never called

#### Scenario: Full access on 26.1+ runs PhotoKit only
- **WHEN** `backgroundUploadSupported()` returns true and photo access is `GRANTED`
- **THEN** the PhotoKit extension is registered and the app-driven pump is not started

#### Scenario: Limited access on 26.1+ runs the app-driven pump only
- **WHEN** `backgroundUploadSupported()` returns true and photo access is `LIMITED`
- **THEN** the app-driven pump is started and the PhotoKit extension is deregistered rather than merely
  left unregistered
