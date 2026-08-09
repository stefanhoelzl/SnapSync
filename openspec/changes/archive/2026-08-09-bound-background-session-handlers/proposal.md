## Why

Both background-`URLSession` completion handlers — the app-driven upload tier's and the download
session's — are stored in a bare field and released outside any receipt. An unanswered OS handler costs
the app its **future** background wakes: uploads and downloads simply stop happening in the background,
silently and permanently, with no error anywhere.

Three dumps from one field device (iPhone11,2 / iOS 18.7.9 / build 573 / `url_session` tier, 2026-08-05
→ 08-08) show the upload handler is *already* released against work that is still running, on **30 of 30**
wakes, and that the same code path drops a `BGProcessingTask` re-arm the heartbeat depends on. Apple's
documentation additionally requires this particular handler to be invoked on the main thread, which
neither session does.

## What Changes

- **A new `ports/` type, `BackgroundEventsReceipts`**, is the only place an OS completion handler may be
  held. It takes the handler at the moment the OS hands it over — so the deadline is bounded from *then*,
  not from whenever the session happens to drain — releases it after the work that wake triggered, and
  holds more than one outstanding handler so a second wake cannot orphan the first.
- **`UrlSessionUploadController` and `QueuedPhotoDownloadJobs` lose their stored-handler fields.** The
  download handler gains a deadline it has never had, and a log line so its fate stops being invisible in
  a diagnostic dump.
- **Both handlers are released on the main lane**, as `URLSessionDelegate.urlSessionDidFinishEvents`
  requires: *"Because the provided completion handler is part of UIKit, you must call it on your main
  thread."* Today both are released on the composition lane.
- **`BackgroundUploadPump.drive()` stops discarding a coalesced trigger.** A caller that coalesces into an
  in-flight drain now awaits that drain and applies its own re-arm decision against the drain's result,
  instead of returning immediately with its obligations dropped. Without this the receipt above holds for
  a call that returns in 0 ms, and the change would claim a guarantee the field log contradicts.
- **A `:test:architecture` guard confines OS-handler storage** to `BackgroundEventsReceipts`, in the shape
  `KeychainContainmentTest` already uses for `SecItem*`. `IosUrlSessionUploadPlatform`'s
  `var onBackgroundEventsFinished` becomes a constructor `val`, so the guard's allowlist never contains
  something that is not an OS handler.
- **`ios-app-shell` is corrected**: it states the receipt type lives in `:domain` `model/`; `OsReceipt`
  lives in `ports/`, and the new type joins it there.

## Capabilities

### New Capabilities

None. Every behaviour here belongs to a capability that already exists.

### Modified Capabilities

- `ios-app-shell`: the OS-completion-handler requirement gains the handover-bounded hold, the
  multiple-outstanding-handlers case, and the main-thread release; its zone claim is corrected from
  `model/` to `ports/`.
- `ios-url-session-upload`: a coalesced pump trigger awaits the in-flight drain and re-arms on its result,
  rather than returning with its scheduling intent discarded.
- `photo-download`: the download session's completion handler is carried by a receipt with a deadline, and
  its adoption is logged.
- `architecture-guards`: a new requirement for the OS-handler confinement gate.

## Impact

- `:domain` `ports/` — new `BackgroundEventsReceipts`; `OsReceipt` gains an optional release lane.
- `:domain` `feature/upload` — `BackgroundUploadPump.drive()` coalescing semantics; two existing
  coalescing tests must move from re-entrant to concurrent triggering.
- `:domain` `feature/download` — `QueuedPhotoDownloadJobs` loses its stored handler.
- `:app:ios` — `UrlSessionUploadController` loses its stored handler; `SnapSyncRoot` supplies the release
  lane.
- `:adapter:ios:app-only` — `IosUrlSessionUploadPlatform`'s callback slot becomes a constructor `val`.
- `:test:architecture` — one new guard.
- No backend, schema, or UI change. No migration.
