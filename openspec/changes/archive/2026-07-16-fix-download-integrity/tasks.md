## 1. Establish what can and cannot be shown

The obvious opening — a failing test — is not available, and the reason **is** the finding. The seam carries
no response and no byte count, so a test cannot supply an input meaning "this transfer was a 502". There is
no wrong answer to observe, only a question that cannot be asked. Do not fake one; record the evidence that
does exist, then write the test the moment the seam can express it (task 3).

- [x] 1.1 Record the demonstration that *is* available, in this change directory, as archive evidence:
      `IosDownloadTransport.onFinished` (`:68`) reads `taskDescription` and nothing else off the task, while
      `downloadTask.response` sits on the same object; `DownloadTransportHost` (`DownloadTransport.kt:20`)
      declares `destinationFor`/`onStaged`/`onCompleted(description, error)` and no outcome;
      `DownloadController.kt:110` logs `"import deferred … // retried later"` over bytes that
      `store.markStaged` has already made permanent. Code, seam, contract — no test.
- [~] 1.2 **Unanswerable by any test in this repo, and not reachable on device with existing machinery.**
      The question is what *URLSession* does — does a `502` reach `didFinishDownloadingToURL` with a nil
      error, and does a real short read reach it at all? Every test here fakes the transport, so a test
      asserting "a 502 arrives at `onFinished`" asserts *what the fake was told to do* and passes whatever
      the truth is. That is worse than no test: it would read as evidence. Only the platform can answer a
      question about the platform.
      On-device is blocked twice over (see 1.4): no hook can aim the app at a failing URL, and there is no
      foreign content to download at all. **The non-2xx half is documented Apple behavior and is the case
      that certainly occurs; the short-read half stays open.** It costs nothing: D3 makes the length check
      free, and the code is correct under either answer. What is lost is knowing whether the length check is
      load-bearing in the field or inert — informational, not a correctness gate.
- [x] 1.3 Poison-pill claim **confirmed — by the schema, which is stronger than the device observation this
      task asked for.** `DownloadStore.sq` proves the shape outright rather than sampling it:
      `selectPendingResources: WHERE r.stagedPath IS NULL …` — a staged resource is **never re-planned**, so
      the bytes are never re-fetched; and `selectImportableAssets: WHERE a.state != 'IMPORTED' AND NOT
      EXISTS (… stagedPath IS NULL)` — an asset whose resources are all staged stays importable **forever**
      until it imports. Garbage that reaches `markStaged` is therefore permanently un-re-downloadable and
      permanently importable, and `DownloadController.kt:110` retries it (`// retried later`) on every
      reconcile.
      *Honest limit: the schema proves the loop's **shape**; its **trigger** — that `PHAssetCreationRequest`
      rejects an XML error body — remains inferred. Both branches justify the fix: if it rejects, the asset
      loops forever and the photo never arrives; if it accepts, a non-photo lands in the library marked
      imported. The fix stands under either.*
      *The harness could not have shown this regardless: `FakePhotoLibraryImporter` imports anything, and
      the world stages synthetic paths, never bytes — so with the predicate defeated the world "imports" a
      502 body successfully instead of looping. Reproducing the pill needs byte-validating import, which the
      world does not model.*
- [x] 1.4 **Why the device route is closed, recorded so the next person does not re-run it.** Two
      independent blockers, either fatal. **(a) Nothing can make a download fail:** download URLs are
      presigned S3 links minted by the backend's union endpoint and fetched straight from bunny — no proxy
      in between — and no dev hook touches the download path (`EVENT_ID`, `EVENT_LINK`,
      `FORCE_URLSESSION_UPLOAD`, `FORGE_STATE`, `POLICY_PROBE`, `QR_OUT`, `SEED_PHOTOS`, `SEED_POLICY` is
      the complete set). **(b) Nothing can make a download happen:** a transfer starts only for a *foreign*
      resource, and this device's reachable events hold none — its own event's assets are all its own
      (`0 foreign planned`), and the two events that once held foreign assets are now empty. Verified on the
      SE2 against a re-signed Debug build of this change: it installs, launches, attests, joins, reconciles,
      and registers push with no regression from the seam change — and plans zero downloads, correctly.
      **Follow-up that would open this class up:** a download-URL-rewrite launch hook, the natural sibling of
      `SNAPSYNC_SEED_POLICY` and inert in production for the same reason. Plus a second enrolled device for
      (b).

## 2. Widen the seam so the question is askable

- [x] 2.1 Add the outcome type to `commonMain` — status, expected bytes, received bytes. Nothing Obj-C, no
      `NSURLResponse`: the seam is the boundary and the type crosses it as data.
- [x] 2.2 Extend `DownloadTransportHost` so a finished transfer's outcome reaches the host **before**
      staging. The check runs before the move, not after (D4): `moveToStaging` does `removeItemAtPath` then
      `moveItemAtURL`, so staging a rejected body would destroy a previously-good file at that path.
- [x] 2.3 Keep `IosDownloadTransport` an edge: read `statusCode` / `expectedContentLength` off
      `task.response`, stat the temp file, hand both over. No judgement in `iosMain` (D1).

## 3. Decide in tested common code

- [x] 3.1 Implement the predicate in `QueuedPhotoDownloadJobs`, joining the queue, window, description codec,
      staging-path derivation and URL guard that the edge's own KDoc says live there under `commonTest`.
- [x] 3.2 Write the test that could not exist before 2.2, in `commonTest` (JVM **and**
      `iosSimulatorArm64`): non-2xx rejected; known-and-short rejected; known-and-exact accepted; unknown
      length accepted; over-length accepted. The last three are the ones that matter most — D3 is where
      over-eagerness would fail *working* downloads, which is worse than the defect.
- [x] 3.3 Assert the store consequence, not just the predicate: a rejected transfer never reaches
      `markStaged`, and the resource remains re-downloadable. The predicate returning `false` is not the
      behavior under test — the asset not becoming a poison pill is.
- [x] 3.4 Assert a rejection frees the window slot so the queue refills. A rejected transfer that leaks its
      slot would stall every subsequent download — a second way to lose photos silently.

## 4. Make the harness able to force it — by faking the right layer

The original 4.1 said "extend the world's download fakes with the outcome". That is not implementable: the
world fakes `PhotoDownloadJobs`, the layer **above** the code being tested, so an outcome added there would
forge the answer rather than exercise the predicate (D6). The world fakes the transport instead.

- [x] 4.1 Add a `DownloadTransport` fake to `:test:world` that records started transfers and lets the
      operator deliver a finish with a chosen `TransferOutcome`, defaulting to a healthy one. It mirrors the
      real delegate's sequence — `accepts` first, then `destinationFor`/`onStaged`, then `onCompleted`
      regardless — or the world will not reproduce the ordering the fix depends on.
- [x] 4.2 Compose the **real** `QueuedPhotoDownloadJobs` over it and delete `FakePhotoDownloadJobs`. Wire
      `onStaged` to the real controller, as `SnapSyncRoot` does. Do not leave a scope-free fallback: two
      download paths is a second way to drive that can rot or lie (D6).
- [x] 4.3 Give `World` the driver's `CoroutineScope` and update every construction site (49, all the
      identical shape `= World()`, across 13 files; inside `worldTest` the scope is `this`, and the
      inspector already holds one). *An earlier draft of this task justified it by `runTest`'s scheduler
      and `advanceUntilIdle` — wrong: `worldTest` is `runBlocking` on both targets and `:test:world` has no
      test scheduler at all. The real reason is ownership and lifetime (D6).*
- [x] 4.4 Rework `stageAllDownloads()` to deliver finishes through the fake transport rather than calling
      `downloadController.onResourceStaged` directly, and add the operator's bad-transfer lever. **Await the
      staging the real jobs launch** — `onStaged` is not a suspend seam, so without a join the action would
      return before importing and every download assertion would race (D6).
      *Existing world tests needed one edit beyond the scope argument, and it was legitimate rather than a
      sign of a bad composition: `downloadJobs.pending()` was an inspection method on the deleted fake. The
      real jobs have no inspection seam and their description codec is `internal` to `:capability:download`,
      so inspection moved — `DownloadEchoTest` now asks the transport whether a transfer actually started
      (the stronger claim), and the world records requests for the inspector's rows.*
- [x] 4.5 Add a world test: a forced `502` stages nothing and the resource stays PENDING for retry — the
      end-to-end shape of the bug, which no unit test of the predicate can show.
- [x] 4.6 Add the lever to the world inspector so the full-stack harness can drive it (capability
      `full-stack-harness` is unaffected — the inspector is its right pane, not its contract).
- [x] 4.7 **What composing the real jobs immediately found, and why task 4 paid for itself.**
      `BackendStore.syntheticUrl` returned `world://<deviceId>/<filename>`. The real jobs guard every url
      with `isFetchableUrl`, which passes only `http`/`https` — because handing a background `URLSession` a
      non-HTTP url raises an uncatchable Obj-C exception. So every world download is skipped as unfetchable,
      and the first run of the composed stack failed two integration tests that had "passed" for weeks. The
      world had been proving downloads work over a scheme production refuses to fetch, because the fake it
      replaced the jobs with never looked at the url. Now `https://world.store/<deviceId>/<filename>`.
      This is the class of defect the world exists to catch and structurally could not.

## 5. Verify

- [x] 5.1 `./gradlew build` and `./gradlew compileIosMainKotlinMetadata` (the Linux-runnable iOS proxy).
      *Both green. The proxy matters here: `outcomeOf` is the only new `iosMain` code and reads
      `NSHTTPURLResponse.statusCode` / `expectedContentLength` / `NSFileSize` through cinterop.*
- [x] 5.2 Drive the world harness headlessly (`:test:harness-driver:driveWorld`): force a short read, confirm
      the asset does not import and does re-download; confirm a normal download still imports.
      *Driven through the real buttons. Foreign-download preset → `▶ Invoke extension` (the reconcile is what
      enqueues; without it the stage buttons are disabled and a click is silently a no-op — the first attempt
      at this was vacuous for exactly that reason). Then: `Stage as 502` → gallery stays `(empty gallery)`,
      downloads stay pending. `Stage short read` → identical. `Stage all pending` → imports as
      `imported-foreign-0-foreign-0-a1 ⛔ upload-suppressed`, phone pane reads **In sync**. Reject leaves the
      resource re-downloadable; the retry imports.*
- [~] 5.3 Inherits 1.2's blockers entirely — see 1.4. What the device *did* verify against a re-signed
      Debug build of this change: install, launch, attest, join, reconcile, push-register, and a correctly
      planned zero downloads. The seam change regresses nothing. The `transfer finished` log line added for
      this task stays: it is the line that answers 1.2 the moment a foreign photo exists to download, and it
      logs every finished transfer rather than only rejections precisely so the accept path is observable.
