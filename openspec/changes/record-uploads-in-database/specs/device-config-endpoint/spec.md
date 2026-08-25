## REMOVED Requirements

The capability is dissolved. The config document stops being an object under `devices/<deviceId>.json` and
becomes a `device_records` row.

### Requirement: Device config write route
**Reason**: Route mechanics; the destination changes from a storage key to a row.
**Migration**: `api-endpoints` → *Device config write*.

### Requirement: Config document shape — push token
**Reason**: The document's content is decided by `push-registration`, which is where it is now stated once.
**Migration**: `api-endpoints` → *Device config write* (cites it); `push-registration`.

### Requirement: Addressed by device id, authorized by token
**Reason**: Addressing is route mechanics; authorization is a duplicate of `device-attestation`'s rule.
**Migration**: `api-endpoints` → *Path parameters are validated before any upstream request* and *Device
config write*; `device-attestation` → *Ungated routes are a closed list*.

### Requirement: Last-write-wins and faithful outcome
**Reason**: Route mechanics, stated once for every route.
**Migration**: `api-endpoints` → *Device config write* and *Faithful outcome — no partial success, no
partial list*.

### Requirement: Config removed when the device is fully orphaned
**Reason**: A sweep rule, not an endpoint rule; and "fully orphaned" is now decided by a query over
memberships rather than by a directory scan.
**Migration**: `scheduled-cleanup` → *Stale-asset collection*.

### Requirement: The device-config write requires a device token
**Reason**: A duplicate of the rule `device-attestation` owns.
**Migration**: `device-attestation` → *Ungated routes are a closed list*.
