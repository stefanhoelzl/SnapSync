# ios-url-session-upload — delta for move-features-download-album-creation

## MODIFIED Requirements

### Requirement: App-driven upload host below iOS 26.1

On iOS versions below 26.1 the **host app process** SHALL perform background uploads (there is no
app-extension target, because `PHBackgroundResourceUploadExtension` does not exist below 26.1). Uploads
SHALL run over a background `URLSession` (`URLSessionConfiguration.background`) whose transfers
continue across app suspension and relaunch the app on completion, driven by the same
`feature/upload` `UploadCycle` used by the `ios-photokit-upload` tier (seated in `:domain` by migration step 5). The app SHALL reuse the
existing edge destination contract unchanged: a deterministic per-resource PUT URL built by
`:domain` `model/`'s `EdgeUploadRequestProvider` (seated there by migration step 3a), with `setAssumesHTTP3Capable(false)` applied
to each request (the same HTTP/3-disable workaround the PhotoKit tier requires). Connections SHALL be
HTTPS-only.

#### Scenario: The app is the upload host below 26.1
- **WHEN** the app runs on iOS 18–26.0 with a joined event and full photo access
- **THEN** the app process performs uploads over a background `URLSession` (no extension is invoked), PUTting each resource to its deterministic edge URL

