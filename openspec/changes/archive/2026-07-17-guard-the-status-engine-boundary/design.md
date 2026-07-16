## Context

```
  domain/gallery/build.gradle.kts:16   api(project(":domain:engine"))    ← must: enumerate() returns List<Resource>
  domain/status/build.gradle.kts:18    implementation(project(":domain:gallery"))
                                        ↓ api propagates
  :domain:status compile classpath ⊇ :domain:engine        ← the scenario says otherwise
  :domain:status imports of engine  = 0                    ← what is actually true
```

`ledger-free-status` (2026-06-29) dropped status's direct engine dependency and wrote the scenario. It was
true of the *dependency* and never true of the *classpath*; nothing ran it, so nothing said so. The
requirement's other scenario — presentation sees neither engine nor gallery — is true, because status→gallery
is `implementation` and the propagation stops there.

## Goals / Non-Goals

**Goals**
- Say something true where the spec says something false.
- Mechanize it, so the next regression is a red build rather than an audit finding.
- Keep the requirement's real force: a status file must not reach the ledger.

**Non-Goals**
- **Moving `Resource`.** See D3.
- **Changing any module's dependencies.** The `api` on gallery is correct — `Resource` is in its public API.
- **Guarding inference.** See D2.

## Decisions

### D1. State the invariant that is enforceable, not the one that sounded strongest

Three candidate sentences:

| | true today? | enforceable? |
|---|---|---|
| "engine is not on status's compile classpath" | **no** | yes (Gradle) — but only after moving `Resource` |
| "no ledger type is reachable from status code" | **no** — a probe importing `LedgerWriter` compiles | no |
| "status declares no engine dependency and references no engine type" | **yes** | **yes** (Konsist) |

The third is what `ledger-free-status` actually achieved, and it is the one that stops the failure the
requirement exists to stop. The first two describe a stricter world that the code has never been in.

Writing down the weaker-but-true claim is the point. A contract that overstates is not stricter, it is
**unreliable** — and the sentence's strength did nothing for a month while the thing it forbade compiled.

### D2. The guard reads text, so it sees imports and not inference — and that is the right scope

Status consumes `Resource` today: `OwnDeviceGalleryStatusSource` calls `enumerator.enumerate(cutoff)` and
reads `it.assetId` / `it.metadata`. No import appears, because the type is inferred from gallery's return.
A text guard cannot see that, and should not try to: it is legitimate. Gallery's API returns engine's
`Resource`, and status consuming gallery's API is the whole point of the module.

What the guard catches is a status file that *names* engine — `import app.snapsync.engine.LedgerWriter`, or
a fully-qualified `app.snapsync.engine.LedgerBackend`. That is the regression the requirement was written
against: status reaching back for the ledger it was freed from. Konsist matches source text, so it catches
the fully-qualified form that imports nothing — the same reason `KeychainContainmentTest` uses it rather
than a linter.

### D3. Not moving `Resource`, and saying why rather than not mentioning it

The scenario becomes literally true if `Resource` moves out of `:domain:engine` into a leaf both engine and
gallery depend on. Gallery would then export no engine, and status's classpath would be clean.

It is arguably the better layering — `Resource` is a *photo's* resource, and it sits in the module that
adjudicates uploads. It is also a real refactor: `Resource` is imported across `:capability:upload`,
`:capability:upload-url`, `:capability:membership`, `:app:ios:photokit-extension`,
`:app:ios:url-session-upload`, and their tests, and `:domain:engine` is currently a leaf, so this inverts an
edge rather than adding one.

Rejected **for now**, on the balance that matters: no code violates the invariant today, and after this
change nothing can violate it silently. The refactor buys a stronger *structural* guarantee for a risk the
guard already covers behaviourally. Recorded as an open question so a future reader knows it was weighed —
the failure mode this repo keeps hitting is not wrong decisions, it is decisions taken and never written
down.

### D4. Why a guard rather than leaving the scenario corrected

Correcting the prose alone would leave the tree exactly as it was on 2026-06-29: a true sentence with
nothing holding it true. The sentence was true then too.

`:test:architecture` exists for "invariants the compiler cannot express", and this is one — the compiler is
*happy* for status to import `LedgerWriter`; that is the problem. The guard costs one file and fails
**closed**, which is the property every other gate shipped this week lacks (`harden-archive-gates`' three
gates are prose and fail open, knowingly — D4 there).

It mirrors `KeychainContainmentTest` down to the vacuity check: a Konsist guard that scans nothing passes,
so the suite asserts its own scope is real. A guard that silently stopped guarding would be worse than none,
because it would report success.
