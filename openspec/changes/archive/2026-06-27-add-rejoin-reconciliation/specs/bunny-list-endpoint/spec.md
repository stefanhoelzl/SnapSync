## ADDED Requirements

### Requirement: Listed filename round-trips with the uploaded filename

The `filename` of each listed entry SHALL be byte-identical to the filename the client used when
uploading the object, so a consumer can match listed objects against local resources by `filename`
equality. The upload path percent-encodes the filename on the wire, the backend decodes it and
re-encodes it into the storage key; the listing returns it such that a filename requiring
percent-encoding (e.g. containing a space or non-ASCII byte) round-trips to the same string the
client uploaded — neither double-encoded nor left in an encoded form.

#### Scenario: A percent-encoded filename round-trips through upload and listing
- **WHEN** a client uploads a filename that requires percent-encoding, and that object is later listed
- **THEN** the listed `filename` equals the original filename the client uploaded (no double-encoding,
  no residual `%XX`)

### Requirement: Listing completeness

The returned array SHALL contain **every** object stored under the event across all devices — not a
capped, sampled, or first-page subset. This completeness relies on bunny native Storage LIST
returning a directory's full contents in a single response (it is non-paginated); should that cease
to hold, the endpoint MUST follow continuation to preserve completeness rather than return a partial
page as `2xx`.

#### Scenario: A device directory with many files returns them all
- **WHEN** a device directory holds a large number of files and the event is listed
- **THEN** the response includes every file in that directory (no page cap)
