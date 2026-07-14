## REMOVED Requirements

### Requirement: Environment-only configuration, fail-closed

**Reason**: The premise inverts. "Read **all** runtime configuration exclusively from Edge Script
environment variables" is precisely the property that killed the backend: bunny issues no scoped API
key, so CI (which rightly holds only a script-scoped deploy key) cannot write those variables — config
therefore lived in a dashboard, drifted, and on 2026-07-02 the script began failing closed at boot with
seven of ten required variables absent, undetected for two weeks. Environment-owned config is a drift
channel that discipline cannot close.

**Migration**: Folded into capability `backend-deployment`, split in two. Non-secret values (zone,
native host, S3 region, S3 host, APNs key id / team id / topic) become **source constants** — see
"Non-secret configuration is source-owned, not environment-owned"; source wins, and the environment is
not consulted for them. The two genuine credentials (`BUNNY_STORAGE_ACCESS_KEY`, `APNS_PRIVATE_KEY`)
remain environment values, still validated once at startup, still fail-closed — see "Secrets-only
environment, fail-closed", which preserves this requirement's spirit (no secret in source; a missing
secret stops the backend rather than degrading it silently) over the two things that are actually
secret.

### Requirement: PUBLIC_BASE_URL is the backend's public origin

**Reason**: `PUBLIC_BASE_URL` has **no consumers**. `config.ts` reads it, trims it, strips its trailing
slash, and makes it required — and `app.ts` never reads `config.baseUrl`. Its only consumer was the
download-proxy route, retired when presigned S3 downloads landed (`changes/archive/
2026-07-02-add-s3-presigned-downloads`) — the same change that made the backend fail closed on it. A
required variable enforcing a value nothing uses.

**Migration**: Deleted outright — the field, the environment constant, the required-variable check, and
its tests. Nothing replaces it. The device-facing origin is already expressed where it is actually
needed: as the baked `BACKGROUND_UPLOAD_URL_BASE` literal on the client, and as the DNS record for
`snapsync.stho.net`. Download URLs are presigned S3 URLs and never contained it.

### Requirement: APNs provider credentials, fail-closed

**Reason**: Only one of the four APNs values is a credential. `APNS_KEY_ID`, `APNS_TEAM_ID`, and
`APNS_TOPIC` are public facts (the team id and bundle id are visible in any shipped IPA; the key id
rides in the JWT header sent to Apple), and treating them as environment-owned secrets subjected them
to the same drift channel — all three were absent from the Edge Script.

**Migration**: Folded into capability `backend-deployment`. `APNS_KEY_ID` / `APNS_TEAM_ID` /
`APNS_TOPIC` become source constants under "Non-secret configuration is source-owned, not
environment-owned". `APNS_PRIVATE_KEY` (the `.p8` PEM) remains an environment value under
"Secrets-only environment, fail-closed" — validated once at startup, fail-closed, never in source. The
APNs host remains unconfigured, chosen per push from the token's `env` (capability
`apns-push-sender`), unchanged.
