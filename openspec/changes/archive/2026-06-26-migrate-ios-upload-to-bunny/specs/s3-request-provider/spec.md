## REMOVED Requirements

### Requirement: Pure request-minting provider
**Reason**: The on-device S3 model is retired (design.md §4). The device no longer holds storage
credentials nor signs uploads; the `:capability:s3` module is deleted.
**Migration**: Replaced by `EdgeUploadRequestProvider` in `:capability:upload-url` (capability
`edge-upload-provider`), which builds an unsigned edge-proxy `PUT` URL.

### Requirement: Object key — resources/ prefix, injective encoding
**Reason**: Object placement moves from the flat `resources/<filename>` key to the edge path
`event/<eventId>/device/<deviceId>/file/<filename>`; the storage key is composed by the edge
endpoint, not the device.
**Migration**: The injective, deterministic filename encoding now lives in
`edge-upload-provider` (Edge URL composition); the `resources/` prefix is dropped — the
`<eventId>/<deviceId>/` placement replaces it.

### Requirement: SigV4 query-presigned PUT
**Reason**: All storage authorization moved to the external edge endpoint (native bunny `AccessKey`,
no payload hash). On-device SigV4 presigning — and the unresolved `UNSIGNED-PAYLOAD` background-upload
risk it carried — no longer exist.
**Migration**: None on-device. The edge endpoint (capability `bunny-upload-endpoint`) performs the
authenticated write; the device PUTs plain bytes to a stable, unsigned URL.

### Requirement: Metadata to x-amz-meta headers
**Reason**: The bunny native Storage API supports no custom metadata headers (design.md §3.5), and
iOS resources carry empty metadata. No `x-amz-meta-*` headers are produced.
**Migration**: Downstream reconstruction reads identity from the key path and EXIF from the image
bytes; no header-carried metadata.

### Requirement: Returned request shape
**Reason**: The returned request no longer carries auth query parameters or a signature.
**Migration**: `edge-upload-provider` defines the new request shape — `Content-Type` only, a plain
edge URL with no query string.

### Requirement: Expiry policy
**Reason**: The edge URL is stable with no expiry; there is nothing to presign or to age out.
**Migration**: `edge-upload-provider` guarantees stable, no-expiry destinations; a retry re-PUTs the
identical URL.

### Requirement: Configuration contract
**Reason**: The provider no longer takes an `S3Config` (`bucket`/`region`/`endpoint`/credentials);
`S3Config` and `S3ConfigPayload` are deleted.
**Migration**: `edge-upload-provider` takes plain-string `host`/`eventId`/`deviceId`; the runtime
config payload is `EventConfigPayload` (`{eventId}`) in `:capability:config`.

### Requirement: Deterministic signing via injected clock
**Reason**: There is no signing, so no signing timestamp and no clock dependency.
**Migration**: Determinism is now inherent — the URL is a pure function of its inputs (no clock).
