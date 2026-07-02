## ADDED Requirements

### Requirement: Upload-key to assetId round-trip parser

`:domain:gallery` SHALL own a **single** `assetIdFromUploadKey` parser — the exact inverse of its
`uploadKey` derivation — that recovers a resource's `assetId` from a bare upload key
(`<assetId>-<role>.<ext>`). It SHALL be the **only** implementation of that parse: both the
extension-side upload-job reconstruction (`ios-background-upload`, "Completion and retry adjudication")
and the re-join reconciler (`event-rejoin-reconciliation`) SHALL call this one function, replacing any
private per-module copy. Because the parse is now load-bearing at the record path (a mis-parse writes a
wrong or empty `assetId`), the round-trip SHALL be pinned by a test: for every key `uploadKey` produces,
`assetIdFromUploadKey` SHALL recover the original `assetId`. The parser SHALL remain in `:domain:gallery`
so its types never reach `:domain:presentation`'s compile classpath (per "Module placement keeps the
seam off presentation").

#### Scenario: assetId round-trips through the upload key

- **WHEN** `uploadKey` derives a key for a resource with a given `assetId` and role
- **THEN** `assetIdFromUploadKey` applied to that key returns the original `assetId`, for assetIds with
  and without embedded `-`, on JVM and on the iOS simulator

#### Scenario: Both consumers use the one parser

- **WHEN** the upload-job reconstruction and the re-join reconciler each recover an `assetId` from a key
- **THEN** both call `:domain:gallery`'s `assetIdFromUploadKey`, with no private duplicate remaining in
  `:capability:rejoin` or the upload cycle
