## REMOVED Requirements

The capability is dissolved. Its route mechanics move to `api-endpoints`; the decisions behind them move
to the capabilities that own them. Nothing here is deleted without a stated destination.

### Requirement: Streaming proxy PUT
**Reason**: Route mechanics; the surface is now stated once, in `api-endpoints`.
**Migration**: `api-endpoints` → *Byte upload streams to storage and records the upload best-effort*,
which also adds the best-effort `uploaded` record.

### Requirement: Object key from the URL path
**Reason**: Split by kind — parameter validation is surface, key composition is surface, and the key's
**event-independence** is a schema constraint that now has to be stated where the schema lives.
**Migration**: `api-endpoints` → *Path parameters are validated before any upstream request* and *Byte
upload…*; `database` → *Five tables, with resources outside the event ownership chain*, which records that
the byte route's path carries no event and therefore forces `resources` out of the event cascade.

### Requirement: bunny native Storage target and authorization
**Reason**: Route mechanics.
**Migration**: `api-endpoints` → *Byte upload…* (`AccessKey` from configuration).

### Requirement: Last-write-wins
**Reason**: Route mechanics.
**Migration**: `api-endpoints` → *Byte upload…*, final paragraph.

### Requirement: Faithful outcome propagation
**Reason**: Stated once for every route rather than per endpoint.
**Migration**: `api-endpoints` → *Faithful outcome — no partial success, no partial list*.

### Requirement: OPTIONS preflight falls back to plain PUT
**Reason**: Route mechanics.
**Migration**: `api-endpoints` → *OPTIONS preflight falls back to plain PUT*, unchanged.

### Requirement: Device manifest write gated on event existence
**Reason**: The manifest write is no longer a storage proxy; it is a database transaction, and its
existence gate is one clause of it.
**Migration**: `api-endpoints` → *The device manifest write is one atomic database transaction*.

### Requirement: Writes require a device token
**Reason**: One of **seven** statements of a single rule that `device-attestation` already owns, together
with the closed list of exceptions. The copies existed only because there were five endpoint specs.
**Migration**: `device-attestation` → *Ungated routes are a closed list*. No behaviour changes.
