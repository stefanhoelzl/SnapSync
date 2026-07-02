## REMOVED Requirements

### Requirement: Per-event object download route

**Reason**: The download is no longer proxied. The list/union `url` is now a presigned S3 GET URL
(capability `bunny-list-endpoint`) that the device fetches directly from bunny's S3 endpoint, so the
backend `GET /files/device/<deviceId>/<filename>` route is deleted. With no route, there is no
capability left to describe; `bunny-download-endpoint` is retired.

**Migration**: The route's job — turning a listed `url` into object bytes — is done by bunny's S3
endpoint against a presigned URL. The upload `PUT` on the same path is unaffected (it lives in
`bunny-upload-endpoint`).

### Requirement: Single ungated streaming object GET

**Reason**: The backend no longer fetches the object at all — the device reads it directly from bunny's
S3 endpoint via the presigned URL. There is no upstream object `GET`, no streaming pass-through, and no
`AccessKey` on a download path anymore.

**Migration**: None. The single object read is now bunny's S3 endpoint serving the presigned GET; the
storage secret is used only to *sign* the URL (capability `bunny-list-endpoint`), never sent on a
download.

### Requirement: Missing object and unknown event are indistinguishably 404

**Reason**: Absence is now bunny's S3 endpoint's `404` (or `403` on an expired/invalid signature)
returned directly to the device; the backend is not on the path to shape it.

**Migration**: None needed. A missing object still fails the download; the device treats any
non-success as a failed transfer and retries (capability `photo-download`), and a stale signature
self-heals via re-presign on the next foreground reconcile.

### Requirement: Faithful read outcome — status committed before the body

**Reason**: There is no backend read to be faithful about — the device's `URLSession` observes bunny's
S3 response status and body directly.

**Migration**: None. The "status/headers commit before the body, so a mid-body abort is a truncated
`200`" property is now bunny's S3 endpoint's standard behavior, observed directly by the device.

### Requirement: Relayed response headers

**Reason**: The backend relays nothing on a download anymore; bunny's S3 endpoint sets `Content-Type`,
`Content-Length`, and cache validators on its own response, read directly by the device.

**Migration**: The `Content-Length` that makes a truncated transfer a client-detectable short-read now
comes from bunny's S3 GET response; the client-side short-read contract moves to `photo-download`.

### Requirement: Client treats a short-read as a failed download

**Reason**: This was always a **consumer** contract, not an endpoint one. With the endpoint retired it
moves, unchanged in substance, to the consumer capability.

**Migration**: Re-homed to `photo-download` ("Background resource download to durable staging"): a
received body shorter than the response's `Content-Length` is a failed transfer and is retried, now
evaluated against bunny's S3 GET response.

### Requirement: Public download URL format

**Reason**: The download URL is no longer `<PUBLIC_BASE_URL>/files/device/<deviceId>/<filename>`; it is
a presigned S3 GET URL. The "sole authority on the download-URL format" role moves to the capability
that now mints it.

**Migration**: Re-homed to `bunny-list-endpoint` ("Presigned S3 download URL"), which is now the sole
authority and is used by both listing routes, so listing and download agree by construction.

### Requirement: Authorization by event id only

**Reason**: There is no download route to authorize. Reaching an object now requires a valid presigned
URL (the query signature is the capability), which the listing hands out under the same event-id /
device-id access model it already enforces.

**Migration**: None. The access model is unchanged (listings remain the ungated, capability-addressed
surface); the download itself is authorized by the presigned signature. The bunny account API key is
never exposed (a rule the listing capability already carries).
