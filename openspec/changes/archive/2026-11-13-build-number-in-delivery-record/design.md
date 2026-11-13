# Design

## Context

- **The delivery context** (`changes/archive/2026-11-11-delivery-record`, D8): every app request — the
  gateway's and every background transfer's — carries one header, `x-snapsync-context: <key>=<value>;…`, written
  by the parts of `DeliveryContext` (`core/data/.../backend/DeliveryContext.kt`) in the words `DeliveryFacts`
  gives each fact. `BuildContext` writes `os`, `model`, `flavour` from `BuildInfo`; the app's graph
  (`BackendGraph`) and the upload extension's (`ExtensionGraph`) both compose it.
- **The api reads it once** (`api/src/delivery-context.ts`): a closed `SHAPES` table; a key it does not know, or a
  value that does not read as its shape, is left out of the record. No route reads it, nothing is refused for it.
  `openapi.yaml` declares the header once, by description, not per key.
- **The version header** `x-snapsync-app-version` carries the marketing version (`X.Y`) and is read by the
  426 minimum-version gate (`api/src/app.ts`, `compareVersions`), the record's `v=` / `app_version`, the error
  tracker's `v` tag, and `deno task logs --v`, `--stats version`, `--devices`.
- **`BuildInfo.buildNumber`** already exists — `CFBundleVersion` on iOS (`?` when unreadable), the version code on
  Android — and the crash channel and the diagnostic dump use it. A CI build's number is `run_number + 2000`, the
  same on both platforms, and `promote.yml` maps it back to its commit. A local dev build carries `1` (the Xcode
  project's and Gradle's default).

## Goals / Non-Goals

**Goals:**
- Every app request's record names the build that sent it, so two builds of one marketing version are told apart.

**Non-Goals:**
- Telling a tester track from the store (TestFlight vs App Store, Play internal vs production): the same binary
  is promoted between them, so the build cannot know — `flavour` stays release/dev.
- A rig-build marker, or a meaningful number for local dev builds.
- A new `deno task logs` view: `--field build=<n>` already filters by it.

## Decisions

### D1. A context key, `build`, not the version header
The build number rides as `build=<n>` in `x-snapsync-context`, written by `BuildContext` beside `flavour`.

*Rejected — extending the version header* (`0.7+2140`): the 426 gate parses that header with `compareVersions`,
which accepts only `X.Y` and sorts anything else as oldest. An api not yet taught the new form would answer 426 to
every request of a build that sends it, so the api would have to ship first and the gate would carry a log-only
fact. The six readers of the raw header would each have to strip the number again. The context exists for
exactly this kind of fact: log-only, never refused, unknown keys dropped — so the app and the api can ship in any
order.

*Rejected — a header of its own*: D8 of the delivery record chose one header over one per fact, because it rides
every operation and every background transfer.

### D2. Digits only, sent as the build has it
The api's shape is 1–10 ASCII digits. The app writes the number as `BuildInfo` gives it, and leaves the key out
when it is not digits (the iOS `?` for an unreadable bundle value) — the context's rule that an unreadable fact
says nothing rather than a guess, as `os` already does. A local dev build sends `1`: `flavour=dev` already marks
such a build, so the record stays truthful without a special case.

### D3. The same number on the app and the upload extension
Both processes read their own bundle; CI stamps one `CURRENT_PROJECT_VERSION` into both, so their requests carry
the same number. A background transfer's headers are fixed when it is queued, so a transfer an older build queued
and the OS sends after an update reports the build that queued it — the build whose code produced the request.

### D4. `os` splits into `os` and `os_version`
Folded in on review: `os=ios/18.6` mixed two facts in one value, so a query for every iOS request had to match a
prefix. The context now carries `os=ios|android` and `os_version=18.6`; the platform is sent even when the OS
states no version.

*No shim*: no release carries the combined form — the context first shipped after `v0.6`, so only internal-track
builds of the last days send it. The api's new shapes drop their `os=ios/18.6` like any unreadable value (nothing is
refused), so those requests record no OS until the phone takes the next internal build. Keeping a reader for the old
form would be compatibility code for test builds alone.

*Why the api checks a value it never interprets* (asked on review, written into `docs/architecture.md` beside the
rule): the header is the caller's and any caller may send it, while the record is kept 30 days. A closed vocabulary
keeps free text — anything that could identify a person — out of the record, keeps each value a short token, and
gives each fact one spelling to query.

## Risks / Trade-offs

- **A dev build's `1` says nothing about which commit it is** → accepted; read together with `flavour=dev`. Giving
  local builds real numbers is build tooling, out of scope.
- **Builds older than this change send no `build`** → the field is absent for them, which reads correctly; no shim.
- **Internal builds before this change lose `os` in the record** (D4) → accepted; they are test builds, replaced by
  the next merge.
