# gallery-status — delta for collapse-harness-onto-shared-composition

## MODIFIED Requirements

### Requirement: Platform backing and a settable fake

The iOS implementation SHALL back `size` with a PhotoKit count. `:adapter:fake` SHALL provide the
honest in-memory implementation (`InMemoryGalleryStatusSource`, re-homed from the deleted
`:domain:gallery` at migration step 10), whose count is a **constructor-injected state cell** —
whoever owns the cell (a test, a `:test:world` wrapper) drives any total, including discovery-lag
(`N` greater than the ledger's completed count) and overshoot (`N` less than the ledger's completed
count), without a device; the fake itself exposes only the port (the fake-honesty gate,
`architecture-guards`).

#### Scenario: Fake count is driven through the owned cell

- **WHEN** a test constructs the in-memory gallery source over its own cell and writes 47 to it
- **THEN** `size.value` is `47` and a collector observes the new value

### Requirement: Module placement keeps the seam off presentation

`GalleryStatusSource` SHALL live in `:domain`'s `ports/` zone (seated by migration step 3a) and its
honest in-memory implementation in `:adapter:fake` (re-homed at migration step 10).
`:ui:presentation` (re-homed from `:domain:presentation` at migration step 9) SHALL NOT depend on
`:adapter:fake`, so no fake gallery type is reachable from presentation code; presentation consumes
gallery-derived counts only through the `feature/status` read-models.

#### Scenario: Presentation compiles without the gallery fakes

- **WHEN** `:ui:presentation` is compiled
- **THEN** `:adapter:fake` is not on its compile classpath, and no in-memory gallery type is
  reachable from presentation code
