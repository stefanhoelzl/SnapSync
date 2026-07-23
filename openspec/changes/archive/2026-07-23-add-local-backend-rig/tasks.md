## 1. Filesystem storage shim (`api/src/dev/`)

- [x] 1.1 Add `api/src/dev/fs-storage.ts`: `fsFetch(root): FetchLike` answering bunny's native Storage
      subset — `PUT` object (create parent dirs), `GET` object (`404` when absent), `DELETE` object
      (`404` tolerated as success by the caller), `GET` directory with trailing slash (JSON array;
      `404` when the directory is absent or empty, so `listDir` yields `null`). Keys map **1:1** onto
      directories and files under `root`.
- [x] 1.2 Synthesize `BunnyEntry` fields in the LIST response — `ObjectName`, `Length`,
      `IsDirectory`, `LastChanged` from mtime. `LastChanged` is load-bearing (the active/departed
      manifest last-write-wins tiebreak and the sweep's upload-time floor), so it must be a
      comparable wall-clock string.
- [x] 1.3 Make the shim **throw** on any URL not under `https://<config.host>/<config.zone>/`, so an
      unshimmed upstream call fails loudly instead of reaching the internet.
- [x] 1.4 Add `api/test/dev/fs-storage.test.ts`: PUT→LIST→GET→DELETE round trip, `404`-on-missing
      object, `404`-on-empty-directory (`listDir` → `null`), percent-encoded filename round-trip
      byte-exact, `DELETE`-of-absent as success, and the out-of-zone throw.

## 2. Dev entry and tasks

- [x] 2.1 Add `api/src/dev/config.ts`: build the dev `Config` from `storageConfig()` (real source
      constants — zone, host, event limits, attest TTL) with `s3Host` set to the live host, dev
      literals for `attestTokenKey` / `adminKey`, and a blank `apnsPrivateKey`.
- [x] 2.2 Add `api/src/dev/serve.ts`: compose `createApp({ config, fetch: fsFetch(root) })`, mint one
      fixed dev token, and serve a handler that (a) serves paths under `/<zone>/` from disk ignoring
      the SigV4 signature, (b) attaches the dev Bearer **only when `authorization` is absent**, and
      (c) otherwise delegates to `app.fetch`. Confirm `main.ts` does not import anything under
      `src/dev/`.
- [x] 2.3 Add the `dev:local` task (bind 127.0.0.1, no tunnel) — the curl loop.
- [x] 2.4 Add the `dev:tunnel` task: fetch `cloudflared` into an ignored directory if absent, start a
      quick tunnel, wait for the `*.trycloudflare.com` hostname, use it as `s3Host`, write it to
      `api/.localdev/host`, and print it plus the ready-to-paste
      `BACKGROUND_UPLOAD_URL_BASE=<host>/api/v1` line.
- [x] 2.5 Widen `deno task check` to cover `src/dev` (it globs only `src/*.ts` today) and confirm
      `deno lint` / `deno fmt --check` pass over the new tree.
- [x] 2.6 Gitignore `api/.localstore/` and `api/.localdev/`.
- [x] 2.7 Verify with curl against `dev:local`: create an event, upload bytes, write a device
      manifest, read the union, and fetch a photo through the presigned URL — with **no**
      `authorization` header, and confirm a request carrying a **bad** token still `401`s.

## 3. `SNAPSYNC_RESET_STATE` trigger

- [x] 3.1 Add the field to `LaunchDirectives` in `:domain` `model/` (presence-triggered, parsed in
      `from(env)`), with a `commonTest` case pinning presence, absence, and the arbitrary value.
- [x] 3.2 Implement the reset in `:domain` `feature/`: ledger `clear()`, discovery `clearToken()`,
      download-store non-terminal drop (**retaining** imported rows and their recorded local asset
      ids), and a **local-only** config clear with no backend notification. Cover it with
      `commonTest` over the fakes, including the retained-imported case.
- [x] 3.3 Wire it into the app shell's launch sequence at the **front** of the fixed order
      `reset → leave → create → event-link`, sequentially awaited, and confirm a forge launch ignores
      it structurally (the forge delegate holds no route to the live stack).
- [x] 3.4 Emit a greppable outcome line naming what was cleared and how many imported rows were kept.

## 4. Boot-banner diagnostics

- [x] 4.1 Extend both processes' boot banner to name the baked upload base, read through the SAME
      source the process's HTTP clients use so the banner cannot disagree with the host actually
      called. No behavior change, no persisted state, no extra I/O. (Ledger counts are deliberately
      NOT read at boot — that would add a launch-time DB touch on a possibly-locked device, and force
      the app's deferred graph assembly, for information the cycle's existing
      `enumeration: N seen, X new, Y already-uploaded` line already carries. Spec delta amended.)

## 5. Remove the `ios.yml` manual dispatch

- [x] 5.1 Delete the `workflow_dispatch` trigger and its `upload_host` input from
      `.github/workflows/ios.yml`.
- [x] 5.2 Delete the "Validate upload_host is HTTPS" step and collapse the build-configuration
      selection to `main` → Release / every other ref → Debug, removing the `UPLOAD_HOST_OUT`
      plumbing and the archive step's host override.
- [x] 5.3 Confirm nothing else references the dispatch (`ios-appstore-promote.yml` resolves
      `build_number → ios.yml run(branch=main)`, which is unaffected).

## 6. Documentation

- [x] 6.1 Add the local-rig runbook to `CLAUDE.md`: the two tasks, the tunnel and host file, the
      ssh-mac `BACKGROUND_UPLOAD_URL_BASE` override line, and `rm -rf api/.localstore` as the reset —
      noting explicitly that this is the deliberate **inverse** of the production rule forbidding a
      whole-zone wipe.
- [x] 6.2 Document the cross-backend trap **inline** in `CLAUDE.md` (not behind a pointer): a
      forgotten `SNAPSYNC_RESET_STATE` uploads nothing with no error, and the boot banner is the
      oracle.
- [x] 6.3 Update `api/README.md` — the "Develop & test" note that a local run targets the real zone
      now points at the rig, and the deploy section notes the `src/dev` tree is excluded from the
      bundle by `deno bundle src/main.ts`.
- [x] 6.4 Remove the `upload_host` dispatch from any `CLAUDE.md` prose that references it.

## 7. Verification (definition of done — both directions on the SE2)

- [x] 7.1 **prod → local**: start `dev:tunnel`, build and re-sign a dev IPA with the tunnel host via
      ssh-mac, install, launch with `SNAPSYNC_RESET_STATE` + `SNAPSYNC_CREATE_EVENT`, and confirm a
      photo's bytes land under `api/.localstore/files/devices/<id>/`, the device manifest is written,
      and the union serves it.
- [x] 7.2 **Download half**: seed or join a second membership so a foreign photo is offered, and
      confirm it downloads to the device through the rewritten presigned URL and imports.
- [x] 7.3 **local → prod**: reinstall a production-baked build with `SNAPSYNC_RESET_STATE`, rejoin a
      real event, and confirm `event-rejoin-reconciliation` seeds already-stored photos as
      `COMPLETED` so nothing mass re-uploads.
- [x] 7.4 Confirm the boot banner names the baked host and ledger counts in `debug.log` for both the
      app and the extension.
- [x] 7.5 Run `./gradlew build`, `./gradlew compileIosMainKotlinMetadata`, and `deno task test` /
      `lint` / `fmt --check` / `check` in `api/`.
