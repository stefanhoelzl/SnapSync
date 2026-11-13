# Proposal

## Why

The delivery record tells which app version sent a request and whether it was a release or a development build,
but not which build of that version. Several builds share one marketing version (every merge to `main` ships one),
so when a version misbehaves the operator cannot tell which build — and so which commit — the affected phones run.
The app already knows its build number; it just never says it.

## What Changes

- **The app reports its build number with every request**, beside the facts the delivery context already carries
  (OS, model, release/development). No new request, no new header. A development build reports the number it was
  built with as it is.
- **The service records it** in the delivery record, under the same rule as every other phone fact: kept when it
  reads as a build number, dropped otherwise, never refused for.
- **The OS is recorded as two facts**, the platform and its version, instead of one combined value — easier to
  query; the spec already names them separately, so it does not change (design D4).
- **The app's version declaration is unchanged** — the build number does not ride in it, so the minimum-version
  check and everything that reads the version stay as they are.
- **The Privacy Policy and the Play data declaration name the build number** among the facts the record holds. No
  store form changes: it is diagnostics, already declared.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `privacy-security`: "The service records how photos are delivered" — the record also holds the app's build
  number.

## Impact

- **App**: the delivery context gains one fact from the build's own constants; the app and the upload extension
  both send it.
- **api**: the delivery context reader accepts one more key; no route, gate or table changes. Deploy order does not
  matter — an api that does not know the key drops it, and an app that does not send it leaves it absent.
- **Docs and listing copy**: the delivery-record table in the architecture doc, the Privacy Policy on the site, the
  Play declarations.
- **No compatibility shim**: older builds simply send no build number.
