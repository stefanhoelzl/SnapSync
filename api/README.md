# api/ — SnapSync backend

The backend: a streaming proxy (Deno/TypeScript + Hono) on **bunny Edge Scripting**, served at
`snapsync.stho.net`. It mints events, streams photo bytes from devices into a bunny storage zone,
records who shares what in a relational store (bunny Database), verifies App Attest device tokens,
wakes members with silent APNs pushes, and proxies the public `site/`. Downloads never pass through
it: devices fetch presigned S3 URLs directly from bunny.

## Run it

```bash
deno task test          # full suite, offline: bunny mocked, SQLite in-memory, no --allow-net
deno task dev:local     # the local rig on 127.0.0.1:8080: filesystem store + real SQLite, no bunny zone
```

Also `deno fmt --check`, `deno task lint`, `deno task check`, `deno task schema:check`, which
together make up the `api-test` required check. `deno task dev:tunnel` adds a cloudflared tunnel so
a phone can reach the rig. Reset the rig with `rm -rf api/.localstore`.

⚠️ Running `src/main.ts` directly targets the **real** zone and database. Use the rig instead.

## Where everything else is documented

- **[docs/architecture.md](../docs/architecture.md)**: where state lives (tables and storage
  layout), the device-token gate, the HTTP contract (routes, versions, statuses), and the source
  layout.
- **[docs/deployment.md](../docs/deployment.md)**: configuration (resolved deployments and secrets),
  the deploy workflow (maintenance window, boot probe, rollback), schema migrations and their safety
  rules, the nightly sweep, and the Edge Scripting limits.
- **[docs/testing.md](../docs/testing.md)**: developing and testing, and the local rig in depth.
- The `local-backend` skill is the runbook for pointing a device or simulator build at a local
  backend.
- User-facing behavior: `openspec/specs/`. Rationale: `openspec/changes/archive/`.
