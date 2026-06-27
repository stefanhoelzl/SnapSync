## Context

The key scheme `<eventId>/<deviceId>/<filename>` was introduced by `migrate-ios-upload-to-bunny`
(the v1→bunny pivot) to scope each device's uploads within an event. Its stated purpose was
collision avoidance for `PHAsset.localIdentifier`, which Apple contracts as unique only *within a
library*. This change is a **partial reversal** of that introduction: the credential-free edge proxy,
the `eventId`-as-capability model, and streaming all stay; only the `<deviceId>` level goes.

The decision rests on three findings established during exploration:

| Claimed benefit of `<deviceId>` | Reality |
| --- | --- |
| Collision avoidance | `localIdentifier` is UUID-based. Flat-namespace collisions are either the same asset (identical bytes, idempotent) or a UUID collision (~0). Over-engineered. |
| Per-contributor attribution | No in-scope consumer. v1 is one-way personal backup; only an out-of-scope external viewer would group by `deviceId`. |
| Structural need for re-join | None. Re-join matches by reinstall-stable `filename`; `HttpEventFilesSource` does not parse `deviceId`. |

```
   BEFORE                                 AFTER
   key   <eventId>/<deviceId>/<file>      <eventId>/<file>
   PUT   /event/:e/device/:d/file/:f      /event/:e/file/:f
   GET   /event/:e/files                  /event/:e/files   (unchanged route)
          → {filename,deviceId,size,…}     → {filename,size,…}

   list  LIST <e>/  → device dirs          LIST <e>/  → files directly
           ├ LIST <e>/<d1>/  → files       (1 subrequest; absent→[]; fail→502)
           └ LIST <e>/<dN>/  → files
           flatten N arrays, drop dirs
```

## Goals / Non-Goals

**Goals:**
- Flatten the storage key to `<eventId>/<filename>` end-to-end (device, backend, specs, design.md).
- Delete the on-device device-id machinery (store, provider, config branch).
- Collapse the list endpoint to a single non-recursive LIST.
- Keep the re-join reconciliation consumer behaviorally unchanged.

**Non-Goals:**
- No data migration / back-compat for the old nested layout (clean break; test data cleared).
- No change to filename composition (`<localId>-<kind>.<ext>`, `/`→`_`, percent-encoding) — only the
  *prefix* it lives under changes.
- No new attribution or per-device-delete mechanism (explicitly foreclosed, see proposal).

## Decisions

**D1 — REMOVE + ADD, not MODIFY, for the list aggregation requirement.** "Cross-device aggregation
via per-directory walk" is removed outright and replaced by a new "Single-directory event listing"
requirement, because the obligation inverts (no longer "fan out and flatten"; now "one LIST, files
are direct children"). A MODIFY would misrepresent the diff.

**D2 — Last-write-wins is widened deliberately.** `bunny-upload-endpoint`'s last-write-wins rule is
unchanged in text but gains cross-device reach under a flat namespace. Rather than add a guard
(existence check, conditional PUT — which the requirement explicitly forbids for faithful-outcome
reasons), we accept it and document the collision analysis. A distinct-photo cross-device overwrite
requires a `localId` UUID collision; a same-photo overwrite is byte-identical.

**D3 — Single LIST, faithful-outcome simplifies.** With one subrequest, "no partial list" reduces to:
the LIST succeeds (`200` with the array, `[]` if the directory is absent) or it fails (`5xx`). The
per-device "any sub-listing fails the whole request" scenario is dropped — there are no sub-listings.

**D4 — Provider config drops to two sources.** `EdgeUploadRequestProvider(host, eventId)`. The
extension's config assembly no longer reads a `deviceId`, so the "device id unavailable → clean no-op"
branch is removed; the only absent-input no-op remaining is "no `EventConfigPayload` in the Keychain."

**D5 — design.md is the source of truth and must be edited in lockstep.** §3.1 (key scheme, the
`<deviceId>` bullet, the "per-device namespacing makes localId sufficient" line, the attribution
trade-off), §3.5 (downstream "group by `<deviceId>`"), §4 (endpoint paths, list response shape), and
the bottom summary table rows are updated as part of this change, not deferred.

## Risks / Trade-offs

- **Cross-device key collision (accepted).** Mitigation is the UUID nature of `localIdentifier`; the
  realistic case is idempotent. If Apple ever ships non-UUID, library-relative ids, this reopens —
  but that is the speculative scenario the interview judged not worth hedging.
- **Lost future optionality (accepted).** Per-contributor grouping and per-device delete are
  foreclosed; reintroducing them later means a filename-level contributor id, not a directory level.
- **Clean-break migration (low risk).** Only disposable test objects exist under the old layout;
  clearing the zone before deploy avoids stranded nested objects invisible to the flat LIST.
