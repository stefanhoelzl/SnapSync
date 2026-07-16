## MODIFIED Requirements

### Requirement: Module placement plugs the engine leak
`SyncStatus`, `SyncState`, `SyncStatusSource`, and the ledger-backed source SHALL live in
`:domain:status`, which depends on `:domain:permission` and `:domain:gallery` (and the event file-list
seam) with **implementation** scope only and SHALL **declare no dependency on `:domain:engine`**. No
status source file SHALL reference an engine type — no import, and no fully-qualified
`app.snapsync.engine.…` — so the ledger status was freed from cannot be reached back for. This SHALL be
mechanically guarded (`architecture-guards`): the compiler is content for status to import `LedgerWriter`,
which is precisely the problem.

Engine is nonetheless **on** status's compile classpath, transitively and unavoidably: `:domain:gallery`
`api`-exports `:domain:engine` because `GalleryResourceEnumerator.enumerate()` returns `List<Resource>`, and
status consumes that seam. Status therefore *uses* an engine type by inference — legitimately; that is what
the seam is for — while *naming* none. The claim made here is the one that is true and can be held: a
stricter sentence sat in this spec for weeks while a probe importing `LedgerWriter` into `:domain:status`
compiled. Cleaning the classpath itself would mean moving `Resource` out of engine; see the decision record.

`:domain:presentation` SHALL depend on `:domain:status` (and
`:domain:permission`) and SHALL NOT depend on `:domain:engine` or `:domain:gallery` — engine and gallery
types stay off presentation's compile classpath.

#### Scenario: Status names no engine type
- **WHEN** `:domain:status`'s source is inspected
- **THEN** it declares no `:domain:engine` dependency and no file references `app.snapsync.engine` — by import or fully qualified — so no ledger type is named in status code

#### Scenario: Presentation compiles without the engine or gallery
- **WHEN** `:domain:presentation` is compiled
- **THEN** neither `:domain:engine` nor `:domain:gallery` is on its compile classpath, and no engine or gallery type is reachable from presentation code
