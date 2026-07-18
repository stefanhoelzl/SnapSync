# architecture-guards — delta for rehome-ui-modules

## MODIFIED Requirements

### Requirement: The zone gates exist before their zones, pending and self-arming

The five zone gates SHALL exist in `:test:architecture` **before** the zones they guard exist —
the gates of requirement "The zone gates": model-purity, ports→model, feature-blindness,
flow-no-ports, presentation-imports — following the fake-honesty gate's self-arming pattern:

- While a gate's scope directory does not exist, the gate SHALL report itself pending — visibly
  (a printed PENDING line naming the scope), never vacuously green-by-silence.
- Once the scope directory exists, the gate SHALL fail if it scans zero sources (the non-vacuity
  twin), and SHALL enforce its import law with **zero gate edits** — migration steps arm gates by
  creating code, never by writing gates mid-move.
- The scan scopes are pinned now, as named assumptions in each gate:
  `domain/src/*/kotlin/**/model/`, `…/ports/`, `…/feature/`, `…/flow/`, `…/compose/` (the
  `:domain` module roots at `domain/`, its `src/` beside the legacy submodule directories until
  they empty), and `ui/presentation/src/**` for the presentation gate.
- The presentation gate SHALL enforce the import-level approximation of its law:
  `ui/presentation` sources never reference the `ports/` or `flow/` packages (imported or
  fully-qualified); the finer no-feature-command-invocation rule remains a review concern until
  it has a mechanical form. The gate's scope is **every** `.kt` under `ui/presentation/src` —
  test sources included, deliberately: presentation's tests are presentation sources, so a test
  that assembles a port-typed stub reintroduces exactly the coupling the gate exists to sever
  (honored at migration step 9, where the two tests assembling the real create use-case over a
  stubbed `EventCreation` port were re-seated as bundle-level choreography, their feature half
  owned by `CreateEventTest` and `:test:integration`).

As of migration step 9 all five pinned scopes exist (`model/`+`ports/` at 3a, `feature/` at 5–6,
`compose/` at 7, `flow/` at 8, `ui/presentation/src` at 9) and every zone gate is **armed** — the
pending state is historical; the self-arming contract stands for any future scope move.

#### Scenario: A gate whose zone does not exist yet

- **WHEN** the guards run while `domain/src` (or `ui/presentation/src`) does not exist
- **THEN** the gate prints a PENDING line naming its absent scope and passes, rather than
  failing or passing silently

#### Scenario: A zone is born and the gate arms itself

- **WHEN** a migration step creates the first file under a gate's pinned scope
- **THEN** the gate enforces its import law on that file with zero edits to the gate

#### Scenario: A scope exists but the scan is empty

- **WHEN** a gate's scope directory exists but the gate's file walk matches nothing
- **THEN** the gate fails — a layout drift must surface as red, not as a gate that passes
  forever
