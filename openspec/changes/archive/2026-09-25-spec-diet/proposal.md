# Spec diet: specs define user-observable outcomes only

## Why

62 specs had grown to ~32,000 lines and ~790 requirements. Only about 12–15% of the requirement text
described anything a user could observe; the rest was implementation detail (modules, classes, ports,
tables, routes, error codes), rationale essays, architecture laws restated per spec, and migration
history. Every design change collided with a spec, so specs blocked new designs instead of protecting
users. The same behavior was restated in 3–6 specs, and five user-behavior contradictions had already
crept in between copies.

The root cause is recognised upstream (OpenSpec issue #1967, "Implementation and test details can end
up in specs because they have nowhere else to go"): the spec was the only artifact that outlived a
change, so everything durable accumulated there.

## What Changes

- **Rule.** A spec defines every outcome a user (host, guest, web visitor) can observe — in the app, the
  photo library, other members' devices, the web, over time, under adverse conditions, and promises
  they rely on but cannot inspect (privacy, security, retention, sharing guarantees). What happens,
  never how. The only external format specified is the invite link / QR. Written into
  `openspec/config.yaml` as WHAT EARNS A SPEC, with the swap test.
- **62 specs → 14 capabilities**, each named after something a user does or gets: create-event,
  join-event, manage-membership, photo-sharing, background-upload, receiving-photos, event-album,
  photo-access, sync-status, event-lifetime, app-update-required, web-site, event-site,
  privacy-security.
- **Engineering knowledge moves to `docs/`** (non-contract explanation, app and api):
  `docs/architecture.md`, `docs/testing.md`, `docs/deployment.md`. `api/README.md` becomes a pointer.
- **Implementation detail** stays in code and KDoc; **measured platform facts** belong in port-contract
  clauses; **rationale** stays in the archived decision records.
- **Archive gates**: the module→capability delta-completeness gate and the dead-types gate are
  removed; a new gate forbids code identifiers in specs. The placeholder-Purpose gate stays.
- Tests no longer parse spec files (`ModuleSetTest`, `RuntimeIdentityTest` carry their lists in code).
- "capability `<old>`" citations in code, workflows and skills are retargeted to the new capability or
  docs file.

**Dropped promise:** a re-upload after an app update is acceptable (events are short-lived).

Not part of this change (separate behavior changes): the create screen preventing ranges > 30 days,
and switching events leaving the old event without blocking.
