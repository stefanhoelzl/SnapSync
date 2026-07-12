## 1. Introduce the transport seam (`:capability:download`)

- [x] 1.1 Define the `DownloadTransport` seam in `commonMain`: create a task for a `(url, description)` and
      return a handle, cancel a handle, and report a transport-level invalidation. Keep it narrow enough that
      the iOS side is a pass-through to `NSURLSession` and wide enough that a fake can model task creation
      failing after the transport is destroyed.
- [x] 1.2 Add a `FakeDownloadTransport` in `commonTest` that records created/cancelled tasks and can be put
      into a "destroyed" state in which task creation fails — the state that reproduces the crash.

## 2. Move the orchestration to `commonMain` and put it under test

- [x] 2.1 Extract the platform-free `DownloadJobs` orchestrator from `IosPhotoDownloadJobs` into `commonMain`:
      the pending queue, the bounded in-flight window (`MAX_IN_FLIGHT`) and its refill-on-completion, the
      task-description codec, the staging-path computation, and the cancellation lifecycle. It implements the
      existing `PhotoDownloadJobs` seam and depends only on `DownloadTransport`.
- [x] 2.2 `commonTest`: **the regression** — cancel all transfers, then enqueue, and assert a task is still
      created. Assert the transport was never destroyed. This test must fail against the pre-change behavior.
- [x] 2.3 `commonTest`: the bounded in-flight window enqueues at most `MAX_IN_FLIGHT` tasks and refills as
      completions arrive.
- [x] 2.4 `commonTest`: the task description round-trips `(deviceId, assetId, resourceKey)`, and a malformed
      description is ignored rather than mis-attributed.
- [x] 2.5 `commonTest`: a resource whose URL is not an `http`/`https` URL with a host is skipped and logged,
      the remaining resources still enqueue, and no task is created for it.
- [x] 2.6 `commonTest`: after the transport reports a system invalidation, the next enqueue builds a fresh
      transport and creates the task on it (self-heal).

## 3. Reduce the iOS adapter to the transport edge

- [x] 3.1 Rewrite `IosPhotoDownloadJobs` (`iosMain`) as the `DownloadTransport` implementation: owns the
      background `NSURLSession` (stable identifier `app.snapsync.download.bg`, non-discretionary, cellular
      allowed, `sessionSendsLaunchEvents`), creates/cancels tasks, and moves finished downloads into
      App-Group staging via `NSFileManager`.
- [x] 3.2 Track created tasks so cancellation is **task-level**: remove `session.invalidateAndCancel()`
      entirely; cancelling cancels each outstanding task and leaves the session valid and reusable.
- [x] 3.3 Implement `URLSession(_:didBecomeInvalidWithError:)` on the delegate: drop the cached session so the
      next task creation rebuilds it with the same identifier.
- [x] 3.4 Keep `adoptBackgroundEvents` working — realize/attach the session on a
      `handleEventsForBackgroundURLSession` relaunch so pending completions are delivered.

## 4. Wiring

- [x] 4.1 Compose the transport into the orchestrator at the single construction site
      (`SnapSyncRoot.kt:202`). `DownloadController`, the `PhotoDownloadJobs` seam, `:test:world`'s
      `FakePhotoDownloadJobs`, and `:test:integration` stay unchanged.

## 5. Specs

- [x] 5.1 Apply the `photo-download` delta: transfer cancellation is task-level and never invalidates the
      session; a system-invalidated session is rebuilt; unfetchable URLs are skipped; the orchestration is
      tested behind a transport seam.
- [x] 5.2 Apply the `ios-url-session-upload` delta: correct the `disable` bullet to task-level cancellation
      and add the "cancellation never invalidates the background session" requirement. **No upload code
      changes** — the implementation already cancels tasks.

## 6. Verify

- [x] 6.1 `./gradlew build` — the new `commonTest` coverage runs on JVM; confirm 2.2 fails when
      `invalidateAndCancel()` is reintroduced (sanity-check that the regression actually bites).
- [x] 6.2 `./gradlew compileIosMainKotlinMetadata` — the Linux-runnable proxy for the iOS source sets.
- [x] 6.3 `npx --yes @fission-ai/openspec@1.5.0 validate --specs --strict`.
- [x] 6.4 Device check (the property `commonTest` cannot prove): join an event with foreign photos, leave it,
      then foreground / re-join and let a download reconcile run. Before this change that sequence aborts.
      Confirm from `Documents/debug.log` (`pymobiledevice3 apps pull app.snapsync Documents/debug.log`) that
      the `[reconcile]` span enqueues downloads with no abort, and that objects stage and import.
