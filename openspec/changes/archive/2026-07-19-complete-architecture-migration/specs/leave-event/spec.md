# leave-event — delta for complete-architecture-migration

## MODIFIED Requirements

### Requirement: Leave is best-effort with no rollback

A failing step SHALL be logged and SHALL NOT roll back earlier steps; there is no transaction across the
producer registration, the config store (the App-Group config file — capability `event-link`),
and the backend notify. The step order — disable
producer, clear
config, then notify backend — SHALL be chosen so the worst partial outcome self-heals: a failed backend
notify SHALL NOT abort or reverse the local teardown (the device still leaves locally; the un-removed
backend membership is the accepted abandon-leak), and if the config clear fails, the event remains
configured (the user is simply still joined, with the producer disabled until the next enable) rather
than leaving a half-torn-down state — the store's own clear ordering (Keychain copy first, file second)
guarantees a partial clear leaves the file, and therefore the membership, intact rather than a state the
migration fallback would resurrect. The backend notify SHALL be dispatched **unconditionally** after the
clear step — a failed `clear()` SHALL NOT suppress it — preserving the independence of each best-effort
step; the resulting transient state (backend told the device left while it is still joined locally)
self-heals when the producer re-enables and re-writes the device manifest. A stale private ledger left in
the extension is reset on the next join via the `joinedEventId` mismatch, not at leave time.

#### Scenario: A failed backend notify still completes local teardown

- **WHEN** the `HttpLeaveNotifier` call fails (offline, timeout, or error)
- **THEN** the failure is logged, the config has already been cleared, and the device leaves locally; the backend membership is simply not removed

#### Scenario: A failed config clear leaves the user joined, not corrupted

- **WHEN** the producer has been disabled but `ConfigStore.clear()` fails
- **THEN** the event is still configured and consistent; re-running leave retries the clear, and no ledger corruption can occur because leave never touched the ledger
