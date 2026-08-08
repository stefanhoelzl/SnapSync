## ADDED Requirements

### Requirement: A resource's content type is the resolved MIME
`Resource.contentType` SHALL carry the **resolved MIME** content type — the same value the platform
adapter resolves iOS-side via `UTType.preferredMIMEType` (falling back to
`application/octet-stream`). The platform's own type identifier SHALL NOT occupy that field, and
SHALL NOT be carried across the enumeration seam at all: the adapter resolves it and reports the
MIME.

This closes a silence rather than changing a rule. The existing seam requires the MIME to be
resolved iOS-side and carried as a raw fact, but names no field to hold it — so the resource was
built with the UTI in `contentType` and the MIME alongside it in `metadata`, and `contentType` is
what `edge-upload-provider` sends as the upload's `Content-Type` header. Naming the field's value is
what stops the two from diverging again.

The change is observable only in the stored object's `Content-Type`: the ledger row already prefers
the metadata MIME, the device manifest is built from ledger rows, and the import path branches on
the manifest's value — so every consumer inside the system already reads the MIME.

#### Scenario: A resource is built from a platform asset
- **WHEN** the enumeration seam maps a platform resource into a `Resource`
- **THEN** `contentType` holds the resolved MIME, and no platform type identifier crosses the seam
  in any field

#### Scenario: An upload request is built
- **WHEN** the upload provider reads `resource.contentType` for the `Content-Type` header
- **THEN** the stored object carries a valid MIME media type rather than a platform type identifier

#### Scenario: The platform cannot resolve a MIME
- **WHEN** the platform returns no preferred MIME for a resource
- **THEN** the adapter reports `application/octet-stream`, and admission is unaffected — the
  fallback is a content-type answer, never an exclusion
