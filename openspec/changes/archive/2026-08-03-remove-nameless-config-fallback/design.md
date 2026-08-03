## Context

`EventConfig.name` is declared `val name: String = ""`. The default exists so that a membership
persisted before the name was reliably set decodes non-null instead of failing. Three pieces of code
compensate for the state that default admits:

| Site | Compensation |
|---|---|
| `model/EventConfig.kt:141` | the decode default itself |
| `feature/membership/MembershipRefresh.kt:117` + `flow/Provision.kt:102` | `fetchNeed`/`TitleNeed` and the branch that fires a details fetch when the name is empty |
| `feature/album/AlbumCoordinator.kt:39` | `|| name.isEmpty()` in `ensureAlbum`'s leading guard |

The compensations are not independent: each exists because the one above it admits a state. That is the
shape this change removes.

Two facts frame every decision below.

**The type has two audiences.** `EventConfig` is simultaneously the domain type (constructed in Kotlin)
and the persisted wire type (decoded from the App-Group file). `kotlinx.serialization` derives the
decode default *from* the constructor default, so "required at construction" and "required in the JSON"
are one knob, not two. Any decision about one is a decision about the other.

**Decode failure is not membership deletion.** A present, current-version payload that fails to decode
maps to `ConfigFileDecode.Unusable` → `ConfigRead.Unavailable` (`ports/ConfigPorts.kt:142-146`), which
is explicitly *not* "no config". `configAfterReload` retains the last good value on `Unavailable`
(`:172-176`), the store logs `NOT 'no config'; caller must defer`
(`FileBackedConfigStore.kt:136-138`), and the reconciler and upload paths read through `ConfigReader`
and defer. Only the UI-facing `StateFlow` collapses it to `null`, and only because that port cannot
express "unreadable". The read-only legacy-Keychain fallback is the one seat that maps an undecodable
item to `ConfigRead.None`, which is already its documented behavior.

## Goals / Non-Goals

**Goals:**

- Make a nameless `EventConfig` unconstructible in Kotlin, enforced by the compiler at every present and
  future construction site.
- Delete the compensations that branch on the state, so no reader has to decide what an empty name means.
- Keep a single, well-tested guard where a blank name could still enter from outside, and document that
  it is the only one.

**Non-Goals:**

- Making a *blank* (whitespace or empty-string) name unrepresentable in the type. `{"name":""}` still
  decodes after this change — see D5.
- Splitting the persisted form from the domain type. That is the structural answer to the type's dual
  audience and is deliberately not attempted here — see D6.
- Any backend change. No route, validator, or marker shape moves.
- Correcting spec drift outside the one requirement block this change already has to edit.

## Decisions

### D1 — Remove the default; `name` becomes required

`EventConfig.name` loses `= ""`.

The reason is **compile-time totality**, not type aesthetics: with no default, every
`EventConfig(...)` site must supply a name, checked by the compiler. Today those sites are `JoinEvent`,
the harness fixtures, and tests; tomorrow they may include multi-event membership work or a config
rebuilt from partial state. Under a default, such a writer silently produces `""` and nothing catches
it. This argument does not depend on the install base, on the backend, or on the proof in D2.

The coupled cost is decode strictness: a persisted payload without the `name` key stops decoding. D2 is
why that cost is zero in practice; D3 is why it is tolerable even if D2 is wrong.

*Alternative considered — keep the default, delete only the compensations.* Coherent, and strictly safer:
`Foreground` refreshes the membership unconditionally (`flow/Foreground.kt:92`) and
`MembershipRefresh` sets the name from `fetched.name`, so a nameless config self-heals on the next
foreground with network. Under that option the album guard would have to **stay**, because it is the one
place a transient blank name becomes a *permanent* artifact: the permission-grant subscription
(`compose/SnapSyncApp.kt:752`) calls `ensureAlbum` with whatever the config holds, and a created album is
mapped in `AlbumMapStore` and reused for the life of the event — so an album titled `""` would outlive
the heal. Rejected because it buys nothing at construction time, which is the whole prize, and because
the failure it protects against is unreachable (D2) while the writer it fails to protect against is not.

### D2 — The proof: no decodable config can be nameless

Removing the default changes behavior **only** for a persisted config that lacks the `name` key. No such
config can exist.

*Leg 1 — every decodable config was written by one of three writers, none of which can omit a name.*
`ConfigStore.save` has exactly three callers in production code:

- `JoinEvent.join` — the name comes from a loaded `EventDetails.Found`, whose `name` is required and
  non-null.
- `MembershipRefresh.refresh` — the name comes from `fetched.name`, written in the same whole-config
  save as the window/retention backfill.
- `ReconfigureEvent` — copies the current config.

*Leg 2 — `maxPhotoDate` already gates every decode.* `EventConfig.maxPhotoDate` is required with no
default. A config that decodes at all therefore carries a ceiling, which means it was either written by
a post-`add-event-date-range` join (`fd609cd5`) or filled by the reconcile backfill — and that backfill
*is* `MembershipRefresh.refresh`, which sets the name in the same save. There is no third way to acquire
a ceiling.

*Leg 3 — no marker has ever carried a blank name.* `EventDetails.Found` can only carry what the event
marker holds. `markerKey` has exactly two mutating uses: the sweep's delete (`api/src/sweep.ts:151`) and
the create handler's write (`api/src/app.ts:774`), which sits behind `validateEventName` (trim, reject
empty or over-long). That validator has existed since `f45821c4`, the commit that introduced
marker-based event creation. There is no rename endpoint; the marker is write-once.

Legs 2 and 3 are claims about what has already happened. They justify the one-time migration cost and
are **spent on ship** — there is nothing in them to re-check later.

### D3 — No expiry trigger, and that is the record

The project's law requires a necessity claim to name its expiry trigger. This one has none, and the
reason is worth recording rather than inventing a trigger to satisfy the form.

The only forward-looking leg of D2 was leg 3 — a dependency on backend behavior — and **this change
eliminates it**. After D5, `HttpEventDirectory` maps a missing *or blank* name to
`EventDetails.Failed`, so the client is total against the backend: a rename endpoint, a marker backfill
script, a hand-edited zone object, a `null` name, a `""` name — every one of them yields `Failed` and
persists nothing. There is no iOS version, no backend release, and no future feature at which this
decision needs re-examination.

What *does* need protecting is the client's own guard, which D5 covers.

### D4 — Delete `fetchNeed`/`TitleNeed` and the `Provision` fetch; keep the name refresh

`fetchNeed` answers `MISSING` only for an empty name, so under D1 it can only ever answer `PRESENT`.
It and the `TitleNeed` enum go, along with `Provision`'s step-6 `when` and the `membershipRefresh` /
`fetchEventDetails` constructor parameters that existed to serve it. Nothing is lost: every provision
route — interactive join, `autoJoin`, switch, headless create — has just loaded or minted the event's
details, so the fetch was redundant by construction, and `Foreground` still runs the same refresh,
backfill, and absence verdict. `compose/` keeps both seams; `Foreground` uses them
(`compose/SnapSyncApp.kt:558,577`).

`MembershipRefresh`'s own name refresh (`:90`) is **kept**, though it is equally unreachable today (the
marker is write-once and there is no rename endpoint). It differs in kind: it converges toward the
backend's truth rather than branching on an impossible *local* state, so it stays correct under any
future rename and is the only path that could ever repair a diverged persisted name. Deleting it would
make the persisted name permanently unrepairable to buy one comparison inside a save that happens
anyway. Its comment changes from "fills a scan-path nameless config" to what it now is.

Consequence for the spec: `Foreground` becomes the sole caller of `refresh`. The requirement that the
rule performs the teardown itself is unaffected — it rests independently on the flow transcriber's
closed grammar (no `when` is transcribable inside an escaping `scope.launch`), which the spec already
states. Only the scenario asserting that *two* triggers reach the same consequence becomes vacuous.

### D5 — `HttpEventDirectory` rejects a blank name, and that guard is now the only one

Removing the constructor default requires the **key**, not a non-blank **value**. `{"name":""}` decodes
perfectly well under D1. So the invariant "no membership holds a blank name" does not live in the type;
it lives at the HTTP boundary — and this change removes its only downstream backup by dropping the album
guard.

`HttpEventDirectory` today rejects a *null* name and accepts `""` as a `Found`. It now rejects blank
too, joining the existing missing-name case as a retryable `Failed`. The comment at that site names it
as the sole guard, and two tests hold it:

- a blank-name response yields `Failed` (the guard itself);
- `{"name":""}` **decodes** successfully at the `EventConfig` level — a test that pins a *weakness*, so
  the next reader cannot infer from `val name: String` that blank is unrepresentable and instead is
  pointed at where the invariant actually lives.

*Alternative considered — keep the album guard as a second line.* Rejected. It tested `isEmpty()`, so it
never caught whitespace and was never a complete second line; and a guard downstream of a *persisted*
value does not prevent the persistence, it only mitigates one consequence of it. "One policy at one
place" says the answer to a single point of failure is to make that point well-tested and well-commented.

### D6 — Not splitting the stored form from the domain type

The dual-audience problem in Context is real and is why D1 has a coupled cost at all. The structural
answer is a `StoredEventConfig` DTO with lenient, per-field decode rules, mapped into a strict domain
`EventConfig` — which would make decode-leniency and domain-strictness independent knobs and turn
`EventConfig`'s hand-argued per-field default paragraphs into one mapping function.

Not done here. It is a far larger change than removing one default, it touches every field's contract at
once, and nothing about this change forces it. Recorded as the shape to reach for if that KDoc keeps
accumulating bespoke absence arguments.

## Risks / Trade-offs

**[The proof in D2 is wrong and some device holds a nameless config]** → The device's config file reads
as `Unavailable`: the setup gate shows for the session, but the file is left intact, no backend leave is
issued, and the upload and reconcile paths defer rather than act, so nothing is destroyed. Recovery is a
fix build. The real hazard is the user's instinctive reaction — creating or joining an event calls
`save`, which overwrites the file and loses the `eventId` for good. Mitigated by D2's three legs and by
the install base being internal TestFlight only; accepted because the alternative forgoes D1's entire
benefit to insure against a state that cannot be constructed.

**[A future writer supplies `""` explicitly]** → The compiler cannot catch a deliberate `name = ""`,
only an omitted one. Not mitigated in the type; D5's boundary guard covers the only path by which such a
value could arrive from outside the app, and the `{"name":""}`-decodes test makes the gap explicit
rather than implied.

**[Someone later re-adds a default, silently undoing D1]** → Nothing mechanical prevents it; the
architecture guards operate on module and zone boundaries, not field defaults. Mitigated by the KDoc
stating the reason as compile-time totality rather than as legacy handling, so a future reader who
re-adds the default has to argue against a stated purpose rather than delete an unexplained line.

**[The `event-link` shape correction touches more than the name]** → Two requirements in that spec
misstate the type, and both are being restated anyway. "Config source and store seams" declares
`minPhotoDate` nullable ("absent = whole-library scope"), omits `startsAt`/`endsAt`/`maxPhotoDate`/
`deletesAt` entirely, and enumerates a stale five-field list in the `save` idempotency clause (now
"field-for-field"). "iOS file-backed config store" omits `endsAt`/`maxPhotoDate`/`deletesAt` from its
payload field list. Both contradict `photo-selection-policy` and the shipped type. Correcting them
enlarges the spec delta with material this change did not create.
Accepted: the blocks are being rewritten anyway, `openspec validate` checks structure and not truth, and
leaving a known contradiction inside a requirement this change re-authors would be a worse outcome than
a wider diff.

## Migration Plan

No migration step, no operator action, no sequencing constraint.

The install base is internal TestFlight. D2 establishes that no device can hold a config the new decoder
rejects, and D3 establishes that no future backend or client behavior can create one. `SNAPSYNC_RESET_STATE`
remains available as the general escape hatch for any device whose durable state needs voiding, but this
change gives no new reason to reach for it.

Rollback is reverting the commit: the persisted format is unchanged (the same keys, the same values),
so a device provisioned by either build is readable by the other.

## Open Questions

None.
