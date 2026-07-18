# gallery-status — delta for rehome-ui-modules

## MODIFIED Requirements

### Requirement: Module placement keeps the seam off presentation

`GalleryStatusSource` SHALL live in `:domain`'s `ports/` zone (seated by migration step 3a) and its
settable in-memory implementation in `:domain:gallery` — the fakes' interim home until they re-home
to `:adapter:fake` (migration step 10). `:ui:presentation` (re-homed from `:domain:presentation` at migration step 9) SHALL NOT depend
on `:domain:gallery`,
so no fake gallery type is reachable from presentation code; presentation consumes gallery-derived
counts only through the `feature/status` read-models.

#### Scenario: Presentation compiles without the gallery fakes

- **WHEN** `:ui:presentation` is compiled
- **THEN** `:domain:gallery` is not on its compile classpath, and no in-memory gallery type is
  reachable from presentation code
