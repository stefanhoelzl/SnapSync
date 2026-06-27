## REMOVED Requirements

### Requirement: Environment-only configuration, fail-closed

**Reason**: The runtime configuration contract is no longer specific to upload — it is read by every
endpoint (create, upload, list, and now download, which adds `PUBLIC_BASE_URL`). It is relocated to a
new shared `backend-config` capability so a single spec owns the env-only, fail-closed inventory.

**Migration**: No behavior change. The identical requirement (zone, host, `AccessKey` validated once
at startup, fail-closed) now lives in `backend-config`, extended with the additional required var
`PUBLIC_BASE_URL`. The upload endpoint continues to read its config the same way, now from the
shared contract.
