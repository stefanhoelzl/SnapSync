## MODIFIED Requirements

### Requirement: The ledger records the destination a job was sent to

A ledger row SHALL carry the **destination path** the upload for that row was addressed to, recorded at the
moment the row is written as in-flight — the same write that records the request, carrying the request it
already holds. No second write, no new ordering, and therefore no window in which a job exists whose
destination the ledger does not know that did not already exist.

The column exists so a row is recoverable from **what the external system persisted**. The OS-driven upload
tier hands a destination request to the platform and the process dies; when the platform returns the
finished job, the destination is the only field reliably present — `resource` is nil for a succeeded job —
and the row must be found from it. Under the previous byte-route shape the ledger key happened to be the
destination's last path segment, so recovery was free; that was an accident of formatting, not a decision,
and it does not survive a route that names identity in its path.

The value SHALL be the URL's **path**, not the whole URL: the path is what the platform must preserve in
order to perform the request at all, while a query or header may be normalized by a store this system does
not control.

The column SHALL be **nullable**, and a row without it SHALL remain fully usable. Rows written by a build
that predates this column exist on every device that upgrades. No tier recovers a job's row by any other
route: the v1 last-segment fallback is retired (capability `ios-photokit-upload`; decision record
`changes/retire-legacy-key-fallback`), so a returned job can reach only a row that recorded its destination.

Recording a destination SHALL NOT make the ledger key event-dependent or expiry-dependent. The key remains
the bare, event-independent object name (see "Event-independent key"), and the destination is stable with
no expiry, so a row's recorded destination stays valid for as long as the row does.

#### Scenario: The destination is recorded with the in-flight write

- **WHEN** a row is recorded as requested for an upload that has just been created
- **THEN** the row carries the destination path that upload was addressed to, written by that same
  operation

#### Scenario: A row is recoverable by its destination

- **WHEN** a returned upload job carries a destination whose path matches a recorded row
- **THEN** that row is identified, including when the destination's last path segment is not the row's key

#### Scenario: A row written before the column is still usable

- **WHEN** a row predates this column and carries no destination path
- **THEN** the row reads and writes normally, but no returned upload job resolves to it

#### Scenario: The key is unchanged

- **WHEN** a row carrying a destination path is read
- **THEN** its key is still the bare, event-independent object name, unchanged by the addition

### Requirement: Reader and writer capability split

The ledger SHALL expose a concrete shared `LedgerWriter` carrying both the record operations and the
per-key query (`entry(key): LedgerEntry?`). Record and query semantics SHALL be implemented once in
this shared class, delegating storage to the injected `LedgerStore`. There SHALL be no separate
reader type: the writer is constructed only by the upload cycle's shared assembly (`uploadCore`), once per
process that runs a cycle — the app on **every** iOS version, and the extension on iOS ≥26.1 — and components
that must not record are simply never handed a writer: status read access goes through `LedgerStore`'s read
operations (`assetProgress()`, per `sync-status`), never through a writer instance. The app also invokes the
**reset family** (`clear()`, `resetTo`) on the `LedgerStore` at membership transitions — a leave clears, a
join loads (see "The ledger is the current membership's share set") — through the use cases that own them,
not through a writer. Those are not record operations (see "Storage seam — dumb row store").

**The invariant is code ownership of each write, and that each write is one guarded transaction** — not how
many processes write. Every ledger write SHALL be exactly one of: the running cycle's `LedgerWriter` record
family (and its key-scoped prune), a guarded `markTerminal` — a transport's, or the app's foreground settle of
in-flight rows the backend already stores (capability `upload-state-reconciliation`, "Foreground settles
in-flight rows the backend already stores") — or a named reset-family use case (the join-time load's
`resetTo`, the leave's `clear()`, the device reset). Each SHALL be one storage
transaction whose guard, where it has one, is inside the statement (see "Storage seam — dumb row store"), so
it is safe against any other write landing between its read and its write — from the same process or the
other one. How many processes hold a `LedgerWriter` at once is **not** an invariant: on iOS ≥26.1 under a
full grant both the app's and the extension's cycles run over the one shared ledger, and a cycle in either
process picks only `DISCOVERED` rows and records `REQUESTED` only after its job was created (write-after-act,
capability `sync-engine`), so two cycles overlapping can at worst each upload the same key's identical bytes
to the same destination, and the second terminal write of the pair is a declined no-op. Decision record:
`changes/both-uploaders-active`.

Handing a writer instance only to the cycle is the **mechanism** that confines the record family to the code
that owns it. That mechanism is deliberately relaxed for one operation: `markTerminal` (see "Guarded terminal
write") is declared on **`TransferRecord`** — a narrow interface `LedgerStore` extends, carrying only
`markTerminal` and one read: `entryForDestination` (see "The ledger records the destination a job was sent
to"). That read alone tells a job whose row an authoritative walk deleted from one a transport can still settle
(capability `ios-photokit-upload`, "Completion and retry adjudication"). The per-key row read `get(key)` is
`LedgerStore`'s, not `TransferRecord`'s: a transport needed it only for the v1 last-segment fallback, which is
retired (decision record `changes/retire-legacy-key-fallback`). The relaxation exists because the party the
platform tells that an upload terminated is a platform callback, and it cannot suspend. The ownership holds — the terminal write belongs to the transport whose job terminated, or to the foreground
settle that found the key's bytes stored, and its guard applies it only to a row still `REQUESTED` — while the
type-level codification does not cover it. A spec or a
review that reads the type-level rule as the invariant will reach the wrong conclusion about this call, which
is why both are stated.

A **transport** — an implementation of the upload transfer lifecycle (`BackgroundTransfer`) — SHALL receive a
`TransferRecord` and SHALL NOT receive a `LedgerStore`. What a transport may touch in the ledger is therefore
exactly the one guarded terminal write and the destination read; every other read and write belongs to the
cycle.

No record operation other than `markTerminal` SHALL be added to `TransferRecord` or to `LedgerStore` on this
argument; a further record operation belongs on the writer. The foreground settle is a second *caller* of
`markTerminal`, not a second operation, and it SHALL record only `COMPLETED`.

#### Scenario: Writer reads what it wrote

- **WHEN** a `LedgerWriter` records an entry and `entry(key)` is called on the same instance
- **THEN** the recorded entry is returned

#### Scenario: Record access exists only where the writer is constructed

- **WHEN** a component is composed without receiving the cycle's `LedgerWriter`
- **THEN** it has no record operation available beyond `markTerminal` — it can otherwise read the ledger
  only through `LedgerStore`'s read operations, and write it only through the reset family

#### Scenario: Both processes' cycles record over one ledger

- **WHEN** on iOS ≥26.1 under a full grant the app's cycle and the extension's cycle each record through their
  own `LedgerWriter`, and a platform callback records a terminal upload through `TransferRecord`
- **THEN** every write is one guarded transaction owned by that code, and no write overwrites a settled row
  or claims a job another cycle created

#### Scenario: A transport holds only the narrow surface

- **WHEN** a transport adapter is composed
- **THEN** it is handed a `TransferRecord`, and no other ledger read or write is reachable from it — no
  per-key row read included

#### Scenario: The app resets the ledger through the reset family

- **WHEN** the app leaves an event or loads a join, on any iOS version
- **THEN** the owning use case calls `clear()` or `resetTo` on its `LedgerStore`, not a `LedgerWriter`, and
  records no per-key fact
