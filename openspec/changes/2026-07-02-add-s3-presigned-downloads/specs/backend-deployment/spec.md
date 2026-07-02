## MODIFIED Requirements

### Requirement: Deno Deploy is the active device-facing runtime

While bunny.net drops iOS's zero-window upload SYNs, **Deno Deploy** SHALL be the **active
device-facing runtime** — the runtime the device-facing origin resolves to and that serves the app's
uploads, event creation, and list requests. Photo **downloads** are **not** served by the runtime: the
list/union `url` is a presigned S3 GET URL and the device fetches those bytes **directly from bunny's S3
endpoint**, off the runtime entirely. The bunny Edge Scripting deploy SHALL continue (the intended
long-term runtime) but SHALL NOT be the device-facing origin until that SYN-drop is resolved. Both
runtimes serve the identical bundle, so the active runtime is selected by **where the device-facing
origin points**, not by which deploy runs.

#### Scenario: The device-facing origin resolves to the active runtime

- **WHEN** the app reaches the device-facing origin for an upload, event creation, or list request
- **THEN** the request is served by Deno Deploy (the active runtime), not by bunny Edge Scripting

#### Scenario: Downloads bypass the runtime

- **WHEN** the app downloads a collected photo's bytes
- **THEN** it fetches them directly from bunny's S3 endpoint via the presigned `url`, not from the
  active runtime

### Requirement: Device-facing origin is a custom domain under our control

The device-facing origin SHALL be a **custom domain we control** through our own DNS (a Bunny DNS
zone) — not a runtime-provider vanity hostname. It SHALL be `CNAME`'d to the active runtime and served
with a **publicly-trusted TLS certificate** (default ATS applies; no `NSAppTransportSecurity`
exception ships, so a non-HTTPS or privately-signed origin is unacceptable). The compile-time baked
host (`BACKGROUND_UPLOAD_URL_BASE` / `BackgroundUploadURLBase`) **and** the backend's
`PUBLIC_BASE_URL` SHALL both be **this same custom domain**, so device→backend traffic for uploads,
event creation, and listings shares one origin we own. Photo **download** bytes do **not** share this
origin — they are served by bunny's S3 endpoint (`<region>-s3.storage.bunnycdn.com`) against a
presigned URL, itself a publicly-trusted HTTPS host covered by default ATS with no exception.

#### Scenario: App reaches the backend over the custom domain via HTTPS

- **WHEN** the app issues an upload, event-creation, or list request
- **THEN** it targets the custom domain over HTTPS, which presents a publicly-trusted certificate

#### Scenario: Baked host and PUBLIC_BASE_URL name the same custom domain

- **WHEN** the baked `BackgroundUploadURLBase` and the backend's `PUBLIC_BASE_URL` are compared
- **THEN** both name the same custom-domain origin we control

#### Scenario: Download bytes come from bunny's S3 endpoint, not the custom domain

- **WHEN** the app downloads a photo's bytes via a presigned `url`
- **THEN** the request targets bunny's S3 endpoint over HTTPS (default ATS, no exception), not the
  custom-domain origin
