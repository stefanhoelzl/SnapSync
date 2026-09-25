## MODIFIED Requirements

### Requirement: Module placement keeps the seam off presentation

`GalleryStatusSource` SHALL live in `:domain`'s `ports/` zone (seated by migration step 3a) and its
honest in-memory implementation in `:adapter:generic:fake` (re-homed at migration step 10).
The presentation zone (`:domain:presentation`) SHALL NOT depend on `:adapter:generic:fake`, so no fake
gallery type is reachable from presentation code; presentation consumes gallery-derived counts only through
the `feature/status/readmodel` package.

#### Scenario: Presentation compiles without the gallery fakes

- **WHEN** `:domain:presentation` is compiled
- **THEN** `:adapter:generic:fake` is not on its compile classpath, and no in-memory gallery type is
  reachable from presentation code
