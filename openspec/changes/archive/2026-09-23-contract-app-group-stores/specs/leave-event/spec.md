## MODIFIED Requirements

### Requirement: Leave is best-effort with no rollback

A failing step SHALL be logged and SHALL NOT roll back earlier steps; there is no transaction across the
producer registration, the upload ledger, the config store (the App-Group config file — capability
`event-link`), and the backend notify. The step order — disable producer, clear the upload ledger, clear
config, then notify backend — SHALL be chosen so the worst partial outcome self-heals: a failed backend
notify SHALL NOT abort or reverse the local teardown (the device still leaves locally; the un-removed
backend membership is the accepted abandon-leak), and if the config clear fails, the event remains
configured (the user is simply still joined, with the producer disabled until the next enable) rather
than leaving a half-torn-down state. That guarantee rests on the store: its `clear` either removes the
config or fails, and it SHALL fail — never return — when it could not reach its storage at all (capability
`event-link`, "iOS file-backed config store"), because a `clear` that returned without deleting would show
the setup gate while the persisted membership survived to reappear at the next launch. The backend notify SHALL be dispatched **unconditionally** after the
clear step — a failed `clear()` SHALL NOT suppress it — preserving the independence of each best-effort
step; the resulting transient state (backend told the device left while it is still joined locally)
self-heals when the producer re-enables and re-writes the device manifest. A failed **ledger clear** SHALL
NOT suppress the config clear or the notify: the device still leaves, and the rows it failed to clear are
cleared by the next join before that join loads the ledger (capability `join-event`), so a leftover
`COMPLETED` row can never suppress a needed upload. A config clear that fails **after** a successful ledger
clear leaves the user joined with an empty ledger: the next cycle re-derives the rows from the library, and
at worst re-uploads resources the backend already holds, idempotently to the same destinations.

#### Scenario: A failed backend notify still completes local teardown

- **WHEN** the `HttpLeaveNotifier` call fails (offline, timeout, or error)
- **THEN** the failure is logged, the config has already been cleared, and the device leaves locally; the backend membership is simply not removed

#### Scenario: A failed config clear leaves the user joined, not corrupted

- **WHEN** the producer has been disabled but `ConfigStore.clear()` fails
- **THEN** the event is still configured and consistent; re-running leave retries the clear, and the only
  cost of the already-emptied ledger is an idempotent re-upload of what the backend already holds

#### Scenario: A clear that cannot reach its store fails the step rather than faking it

- **WHEN** a leave runs on a build whose config store cannot resolve its storage
- **THEN** the config clear fails, the failure is logged, the event is still configured, and the backend
  notify is still dispatched — the same outcome as any failed config clear

#### Scenario: A failed ledger clear still leaves

- **WHEN** the producer has been disabled but clearing the upload ledger fails
- **THEN** the failure is logged, the config is still cleared and the backend still notified, and the
  leftover rows are cleared by the next join before it loads the ledger
