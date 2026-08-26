# PROBE — can the OS-driven PhotoKit upload tier run on an iOS simulator?

**Measured 2026-08-26.** macOS 26.5.2 · Xcode 26.6 · iPhone 17 simulator on **iOS 26.5** ·
ad-hoc signed with `iosApp/Configuration/simulator.entitlements` (appex signed first, then the app,
via `scripts/sim-sign`) · `photos=YES` granted with `applesimutils` · local backend
(`deno task dev:local`) on the runner's loopback, `uploadBase = http://127.0.0.1:8080/api/v1`.

Instrument: a throwaway `POST /device/invoke-extension` rig verb that calls the **real**
`UploadExtensionRoot.processRawValue()` in the app process, on its own single thread, with Kermit's
writer list snapshotted and restored around the call. **n=1 host, n=1 runtime.**

## Verdict

**No. Job creation is fatal on this host** — it is not a returnable error, it terminates the process.
Two facts, measured in order.

### 1. The upload-job extension CANNOT BE REGISTERED on a simulator

Under a **full** grant, on a clean device, with the appex embedded in `Extensions/` and ad-hoc signed
(`Identifier=app.snapsync.BackgroundUpload`, `Signature=adhoc`):

```
[Debug] extension disable found no configuration record (3201) — expected on a clean device
[Error] extension enable FAILED: PHPhotosErrorDomain:-1 — the extension is not in the state the app
        believes; uploads will not run and nothing else will report it
```

`isUploadJobExtensionEnabled()` → `false`, reported as `osExtension.enabled: false` on `/device/state`.

The code is a bare **`-1`**, NOT `3311` (`PHPhotosErrorAccessUserDenied`, the partial-grant refusal
recorded in `2026-08-25-correct-partial-grant-registration-refusal`), NOT `3202` (existing record),
NOT `3201`. This is a distinct, previously unrecorded refusal.

### 2. With no registration, `createJob` raises an uncaught ObjC exception and kills the process

Everything up to job creation works. From `ext-debug.log`, written by the extension root running
inside the app process:

```
=== extension process start build=0.1(1) ===
[boot] upload base = http://127.0.0.1:8080/api/v1
→ process
device identity: id=EE5575E3-… via=read(protection=BACKGROUND_READABLE)
GET http://127.0.0.1:8080/api/v1/files/devices/EE5575E3-… → 200 (84ms)
joined 264402f1-… — reset+seeded 0 file(s), cleared cursor
← platform.fetchRetryJobs = 0 job(s)
← platform.drainTerminals = 0 job(s)
gallery: fetched 3 candidate(s)
← platform.discoverResources = 3 candidate(s)
selection policy admitted 3 of 3 candidate(s) → 3 resource(s)
Upload key=…-primary.png attempt=0
→ platform.createJob(key=…-primary.png)            ← no matching ←
```

and then, from `log stream` on the same pid:

```
PhotoKit changes: performChangesAndWait: called at QOS_CLASS_DEFAULT
_isPhotosAccessAllowedWithScope:read-write resolved preflight status as 2
*** Terminating app due to uncaught exception 'NSInvalidArgumentException', reason:
    '*** -[__NSPlaceholderArray initWithObjects:count:]: attempt to insert nil object from objects[0]'
  2  CoreFoundation  -[__NSPlaceholderArray initWithObjects:count:]
  3  CoreFoundation  +[NSArray arrayWithObjects:count:]
  4  Photos          -[PHAssetResourceUploadJobChangeRequest setUploadJobConfiguration:]
  5  Photos          +[PHAssetResourceUploadJobChangeRequest creationRequestForAssetResourceUploadJobWithDestination:resource:type:]
  6  SnapSync        kfun:app.snapsync.ios.upload.IosPhotoKitUploadPlatform…
```

The throw is **inside Apple's frame**, building an `NSArray` from a nil in
`setUploadJobConfiguration:`. Photo access is fine at the call (`preflight status as 2`).

Zero photo objects reached `api/.localstore`.

## What is inference, and what is not

- **Measured:** registration is refused with `PHPhotosErrorDomain:-1`; `creationRequestForJob…` then
  terminates the process with `NSInvalidArgumentException` from inside `setUploadJobConfiguration:`.
- **Inferred, not measured:** that the *absence of a configuration record* is the nil. It is the
  obvious candidate and the two facts are consistent, but nothing measured proves the link.
- **Not measured:** whether the same call from the **registered appex's own process** on a device
  behaves differently. It demonstrably works there — the tier ships — but this probe ran in the app
  process on both counts, so "only the registered extension's process may create jobs" is not
  excluded as a contributing cause. It does not change the outcome for this host either way.
- **Expiry:** re-measure at the next iOS major, alongside the other PhotoKit platform facts.

## Consequences

1. **`createJob` must never run on a simulator** — it kills the app under test with no catchable
   error. A simulator build must contain **no route** to `IosPhotoKitUploadPlatform.createJob`;
   binding by compilation target, not by a runtime choice, is doing real work here.
2. **The substituted-transport arm is not a fallback, it is the only arm.** The rig must play the OS
   for the four job verbs — `createJob`, `fetchRetryJobs`, `drainTerminals`, `retryJob`.
3. **Everything else in the tier is genuinely exercisable here**, and was exercised: the shared
   `uploadCore`, the entry gate, the re-join reconcile, the device manifest, real PhotoKit discovery
   with the real selection policy, real HTTP to a real backend, and cross-root device identity
   agreeing through the App-Group file store. Discovery must therefore stay delegated to the real
   `IosDiscovery`, exactly as `IosPhotoKitUploadPlatform` delegates it.

## Incidental defect found (unrelated to the simulator)

`PhotoKitUploadProducer.start()` logs `background-upload extension re-registered (disable→enable,
cleared REQUESTED)` at Info **unconditionally**, two milliseconds after the Error line saying the
enable FAILED. The success claim is not conditioned on the outcome it just classified. On a device
this would read as a successful registration in `debug.log` whenever one had failed.
