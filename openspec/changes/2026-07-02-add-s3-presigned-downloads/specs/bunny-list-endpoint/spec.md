## ADDED Requirements

### Requirement: Presigned S3 download URL

Each listed object's `url` SHALL be an **AWS SigV4 presigned S3 GET URL** for that object, minted by
the backend against the storage zone's S3-compatible endpoint. The URL SHALL be
`https://<s3-host>/<zone>/<key>?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=…&X-Amz-Date=…&X-Amz-Expires=604800&X-Amz-SignedHeaders=host&X-Amz-Signature=…`
(path-style; `<s3-host>` and region from configuration; `<key>` the bare object key
`files/<deviceId>/<filename>`, each segment percent-encoded so the key stays one flat path), signed
with the storage zone's credentials (Access Key ID = the zone name, Secret = the storage-zone
`AccessKey`/password) and `X-Amz-Expires` of **7 days** (604800 s). The query signature is the **sole**
authorization: a consumer fetches the object **directly** from bunny's S3 endpoint with this URL and no
additional credential. This capability is the **sole authority** on the download-URL format (the former
`bunny-download-endpoint` proxy route is retired), and both the per-device list and the event-wide union
use it, so their `url` fields agree by construction. A **fresh** URL SHALL be minted on **every**
list/union response — there is no stored or cached URL — so each read yields a URL valid for a further 7
days. No response SHALL expose the storage-zone secret beyond the derived signature, and no response
SHALL expose the bunny account API key.

#### Scenario: A presigned S3 GET URL is returned

- **WHEN** a stored object is listed
- **THEN** its `url` is a path-style `https://<s3-host>/<zone>/files/<deviceId>/<filename>` carrying
  `X-Amz-Algorithm=AWS4-HMAC-SHA256`, `X-Amz-Expires=604800`, and an `X-Amz-Signature`

#### Scenario: The URL fetches the object directly from bunny's S3 endpoint

- **WHEN** a listed `url` is fetched with **no** authorization header
- **THEN** bunny's S3 endpoint returns that exact object's bytes (the query signature authorizes the read)

#### Scenario: A fresh URL is minted on every response

- **WHEN** the same object is listed in two separate responses
- **THEN** each response carries an independently-signed `url`, each valid for 7 days from its own
  response time (no cached or reused signature)

#### Scenario: The storage secret is never exposed

- **WHEN** a `url` is minted
- **THEN** only the derived `X-Amz-Signature` appears; neither the storage-zone password nor the account
  API key is present in any response

## MODIFIED Requirements

### Requirement: Normalized asset entry shape

Each array element SHALL be a file object with exactly the fields `filename`, `size`, and `url`. The
field set is closed: an element SHALL NOT carry any other field — no storage key, no last-modified, no
content type, no role. `size` SHALL be the object's byte length as reported by the directory listing.
`url` SHALL be the **presigned S3 download URL** for that object, as defined by this spec's "Presigned
S3 download URL" requirement (the per-device list and the event-wide union share that single authority).
`filename` SHALL be the uploaded filename, decoded back from the stored key. Because each `url` is a
time-limited signed URL, the per-device list response SHALL carry `Cache-Control: no-store`.

#### Scenario: File entry carries the three fields

- **WHEN** a stored object is listed
- **THEN** its entry is `{ filename, size, url }` and carries no other field

#### Scenario: A file url fetches the listed object

- **WHEN** a listed file's `url` is fetched
- **THEN** bunny's S3 endpoint returns the very object that entry describes (per the "Presigned S3
  download URL" requirement)

#### Scenario: The per-device list is non-cacheable

- **WHEN** the endpoint responds `200` with a per-device listing
- **THEN** the response carries `Cache-Control: no-store` (its `url`s are time-limited signed URLs)

### Requirement: Union entry shape

Each array element SHALL be an asset object carrying exactly `deviceId` (the owning device's id),
`assetId` (the device-local asset identity from the manifest), `creationDate` (the asset's capture
timestamp, ISO-8601, from the manifest), and `resources` (a non-empty array). Each resource element
SHALL carry exactly `role`, `contentType`, `key`, `filename`, `size`, and `url`: `role`,
`contentType`, `key`, and `filename` projected verbatim from the manifest resource (`key` the storage
object name, `filename` the human capture name); `size` the object's byte length from the device's
file partition listing; and `url` the **presigned S3 download URL** for that object, as defined by this
spec's "Presigned S3 download URL" requirement (the per-device list and the union share that single
authority, so both agree by construction). The field set is closed: no asset or resource element SHALL
carry any other field.

#### Scenario: Asset entry carries its four fields and owning device

- **WHEN** a complete asset is emitted
- **THEN** its entry is `{ deviceId, assetId, creationDate, resources }` and carries no other field,
  with `deviceId` the device whose manifest it came from

#### Scenario: Resource entry carries the six fields

- **WHEN** a resource is emitted
- **THEN** its entry is `{ role, contentType, key, filename, size, url }` and carries no other field

#### Scenario: A resource url fetches the listed object

- **WHEN** a union resource's `url` is fetched
- **THEN** bunny's S3 endpoint returns the very object that resource describes (per the "Presigned S3
  download URL" requirement)
