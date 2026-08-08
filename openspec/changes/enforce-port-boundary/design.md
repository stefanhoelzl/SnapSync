## Context

A 122-file audit of `:domain` — `model/` (37), `ports/` (34), `feature/` (42), `flow/` (5),
`compose/` (4) — was run to find where the core encodes platform specifics. It was started as
preparation for a second platform and produced a long list sorted by "does this contain Apple
vocabulary". That sort turned out not to predict anything: `SelectionPolicy` was flagged and is
fine; `ConfigFile` was flagged and is benign; the one site that would silently return a *wrong*
answer to a second platform — a `when` over `0L/1L/2L` decoding `UIApplicationState` — was not
flagged at all, because it contains no Apple token.

Re-derived from the code rather than the token list, the findings collapse to two shapes, both
violating a requirement already in force:

```
"anything touching an external system … only through a port interface declared in ports/"

 GAP 1  compose/ hands the core platform touches as function-typed fields
        AppPorts: 48 fields — 25 port-typed, 20 function-typed, 3 plain values.
        15 of the 20 are core coordination — legitimate, and the only way flow/
        can be handed collaborators without naming ports (flow-no-ports gate).
        5 reach out of the process. (UploadPorts: 18 fields, 6 function-typed,
        all 6 coordination.)

 GAP 2  model/ and feature/ name platform constants directly
        4 sites. Every zone gate today inspects IMPORTS; isConfigFileAbsence
        imports nothing — its signature is (String?, Long) -> Boolean and the
        Apple-ness is entirely in the literals.
```

Three findings shaped the design more than the site list did.

**The core already has neutral vocabulary for almost every leak.** `ConfigFileRead
{Content|Missing|Failed}` exists and the adapter already produces it — only the `NSError` translation
table was hoisted inward. `ResourceRole` exists in `model/`; `RawResource` carries the raw
`PHAssetResourceType` int *beside* it. `RawResource` carries `mimeContentType` — already resolved
iOS-side — *beside* the UTI the mapping actually uses. `AppVisibility` exists; `appVisibilityFrom`
decodes Apple ABI integers into it. In four of five cases the adapter reports **both** forms and the
core reaches for the platform one. The fix is deletion, not design.

**The placements are deliberate and documented, not drift.** `feature/album/AlbumMapMigration`'s own
KDoc says it is where it is *"so the one-shot migration is tested on JVM **and** the simulator rather
than living in an untested iOS file."* Three sites make the same trade. `commonTest` runs on both
targets and is cheap; `iosTest` is macOS-only. The architecture applies a standing force pulling
translations inward across the zone boundary, and the gates cannot see it happen.

**That force is answered by an inversion nobody had noticed.** The tests that justify the inward
placement are tautological. `UniversalLinkActivityTest` asserts
`assertEquals("NSUserActivityTypeBrowsingWeb", BROWSING_WEB_ACTIVITY_TYPE)` — a constant compared to
a copy of itself, which cannot fail and validates nothing about iOS. `SceneModeTest` asserts
`appVisibilityFrom(0L) == ACTIVE`, proving only that the `when` says so. Moved to `iosTest`, both can
assert against the real symbols (`NSUserActivityTypeBrowsingWeb`, `UIApplicationStateActive`) and
become tests that can actually fail when Apple changes something. The trade the three sites made was
real coverage for the appearance of it.

## Goals / Non-Goals

**Goals:**

- Make the two violation classes fail the build, over a **pinned** baseline that is exact in both
  directions — five identifier sites and the composition bundles' function-typed inventory (D1, D2),
  each entry carrying its reason, so the debt that remains is legible rather than absent.
- Move each existing violation to the adapter that already holds its inputs, deleting the raw
  platform fields whose only consumer was the translation.
- State, in each gate's own documentation, what it cannot see — so a green run is not read as a
  claim the gate does not make.

**Non-Goals:**

- **This is not Android preparation.** No target is added, no `androidMain` source set, no Android
  dependency. Every site here violates a requirement in force today and is justified without
  reference to a platform that does not exist yet. Where a second platform is mentioned it is as
  the *test* the law already states ("the name must remain correct if a second platform ships"),
  not as a roadmap.
- **The `ports/Keychain` reshape is out of scope** (see D6).
- **`appVisibilityFrom` is out of scope** (see D7).
- Not a prose sweep. 28 sites across the audit carry iOS-provenanced *justifications* in KDoc —
  neutral types explained by `PhotoKit` / `AVCaptureDevice` / `NSISO8601DateFormatter` / `assetsd`.
  That is a real pattern and a real cost to a second implementer, but it is documentation, no gate
  can reach it, and bundling it would swamp the enforceable part.

## Decisions

### D1 — Both gates pin an inventory; neither analyses semantics

**Chosen:** each gate holds a pinned list in `:test:architecture`, exact in both directions, and
fails on any difference.

**Why:** the properties that matter are not decidable from source. Whether a lambda reaches out of
the process cannot be read off its type — `downloadStagingRoot: () -> String` and `deviceId: () ->
String` are identical types where one resolves a platform container and the other returns a value
the composition already holds. A pin records a human judgement once, at the moment it is made, and
forces the next person to make one too.

**Alternatives considered.** *A detekt rule with type resolution* — detekt has no type resolution on
Kotlin/Native source sets, which is the same objection recorded in `KeychainContainmentTest`.
*Konsist call-target analysis* — Konsist exposes source text, not an AST, so resolving what a lambda
body ultimately calls is out of reach. *An allowlist without exact counts* — an allowlist that only
fails on additions rots: it keeps describing code that has moved, which is how the shell gate's
inventory was specified in both directions to begin with.

### D2 — The identifier gate exempts comments, and its baseline is five pins, not zero

**The exemption.** Measured over `model/`, `ports/` and `feature/`: scanning source **including**
comments flags **48** files; scanning with comments **stripped** flags **5**, every one of them a
real identifier in real code. (The audit measured 37 and 6 with a looser token set; the ratio is the
finding, not the exact counts.) A gate at 48 files is not a gate — its allowlist becomes the
codebase, and nobody reads it. The exemption will look like an oversight to a future reader
("shouldn't we check the docs too?"), so it is recorded as normative in the spec rather than left to
be rediscovered.

**The baseline is not zero, and saying it was would have been the more comfortable lie.** The armed
gate (`PlatformIdentifierTest`) pins **five sites**:

| pin | token(s) | why it is pinned |
| --- | --- | --- |
| `model/CompositionMode.kt` | `PHOTOKIT`, `URL_SESSION` | upload **tiers this app defines**, selected by a pure total resolver — not platform APIs the core calls |
| `ports/Keychain.kt` | `Keychain` | D6's deferred family |
| `ports/ConfigPorts.kt` | `Keychain` | D6's deferred family |
| `feature/album/AlbumMapMigration.kt` | `Keychain` | D6's deferred family |
| `ports/OsReceipt.kt` | `URL_SESSION` | a naming slip; see below |

**The split into `accepted` and `deferred` is the decision, not the formatting.** One map would have
made all five read the same way, and they are not the same: `CompositionMode`'s members are a
judgement the owner stands behind indefinitely, while the other four are **live violations of the
law this change is enforcing**, left standing on purpose. Filing them under "accepted" would launder
debt into design — the failure mode a pin list has, and the reason each entry carries a reason. The
`deferred` entries therefore carry **expiry triggers** and the `accepted` one does not.

**The `Keychain` token is kept in the scan deliberately.** Dropping it would have produced the clean
zero baseline and left D6's family invisible to the gate that exists to find it; keeping it means
D6's reshape **cannot land without deleting those three pins**, because the gate is exact in both
directions. The debt is now attached to the thing that removes it.

**Known debt, not previously named: `ReceiptDeadlines.URL_SESSION_EVENTS`.** `ports/OsReceipt.kt` is
the port *for* OS entry points and its two sibling deadlines (`SILENT_PUSH`, `BACKGROUND_TASK`) are
already neutral, so this one member names the technology where its neighbours name the need. It is a
naming slip rather than a structural one — no `URLSession` type or value crosses the port — and it
is not fixed here only because renaming it touches the tier it belongs to. **Expiry:** it dies with
the iOS 18–26.0 app-driven tier.

### D3 — Translations move outward, to the adapter that owns their inputs

Not: keep them central and neutralise their inputs. `PhotoKitCandidateSource` already holds a live
`PHAssetResource` when it fills `type = resource.type` — it can call the role mapping on that line.
`FileBackedConfigStore` already holds the `NSError` when it calls `isConfigFileAbsence`.

**Alternative considered:** invent neutral input types so the translation can stay in `model/` (e.g.
a `ConfigFilePresence` enum produced by the adapter). Rejected because it moves the same decision to
the same place while adding a type — and because the "rules in features" defence for keeping it
central does not hold: mapping `NSCocoaErrorDomain 260` to `Missing` is not a rule about membership,
it is translation of a platform encoding, which is what an adapter is for. The rule about membership
(`Missing` → consult fallback → `None` → leave) lives in `configReadViaFile` and does not move.

### D4 — `now` reaches for the existing `Clock` port rather than getting a new seam

`ports/Time.kt` declares `Clock`, `:adapter:generic:app` implements `SystemClock`, and the shell
supplies `now = { (NSDate().timeIntervalSince1970 * 1000).toLong() }` anyway. Two clocks are
therefore live in one composition — the port for the UI formatter path, the lambda for the domain —
and `SnapSyncApp` twice wraps the lambda's millis back into an `Instant` it could have had directly.

They agree in production, but they are two things a test must pin, and pinning one leaves
`CutoffFormatter` and `MembershipRefresh.confirmedGone` reading different times. `confirmedGone`
decides a membership is over.

The wider point recorded here: a bypassed seam is indistinguishable from an absent one to everything
downstream, which is why the spec scenario is written about *reaching past an existing port*
specifically.

### D5 — Three of the five seams join existing ports; only two are new

The members and interfaces, by name, so the record says what shipped:

- `downloadStagingRoot: () -> String` → **`StagedBytes.stagingRoot(): String`**, the port that
  already owns staging-file lifetimes (`release(paths)`); where bytes land and what may be reclaimed
  from there is one concern, and splitting it across two seams is how they come to name different
  directories. Consequence: `AppPorts.stagedBytes` loses its `StagedBytes.None` default — "release
  nothing" is a safe no-op, "stage into a directory nobody chose" is not — and `None.stagingRoot()`
  throws rather than answering.
- `presentPhotoPicker: () -> Unit` → **`PhotoAccessRequester.choosePhotos()`**, the port that already
  presents system UI (`openSettings()`) and whose outcome already arrives only through a read-model.
  Same need — hand the user back to the system to widen what this app may see.
- `now: () -> Long` → **`Clock`** (D4). `DeviceAttestation`'s own `now` folds onto it too, so one
  clock is live in the composition rather than three seams for one need.
- `notifyLeave: suspend (eventId) -> Unit` → new **`ports/LeaveNotifier`**, one method
  `suspend fun notifyLeaving(eventId: String): Result<Unit>`.
- `share: (String) -> Unit` → new **`ports/SharePresenter`**, one method `share(text: String)`, with
  a `SharePresenter.None` companion for compositions with no platform surface to reach (the desktop
  harnesses and the world). Inert is honest **only** because the tap is already recorded on the way
  in (`tap.share`), so a run never loses the fact that the user asked.

**`LeaveNotifier` takes no `deviceId`, and the adapter holds it as a thunk.** The port says "*this
device* is leaving": which device is a per-process constant the adapter already needs in order to
address the route, never a choice the caller makes. Taking it as a parameter would widen the port to
"make any device leave any event" — a capability nothing needs, and one an id mix-up could exercise
by accident, in a codebase that has already shipped an incident of two device identities being live
at once (2026-07-20). `:test:world`, which genuinely speaks for another member, binds a second
instance to that id at construction, so the substitution is visible where it is made rather than
buried at a call site. `HttpLeaveNotifier` takes the id as `() -> String` rather than a value because
on iOS resolving it reads the Keychain, and binding eagerly would drag that read into composition and
abort a locked background launch — the same reason `AppPorts.deviceId` is a thunk. It is read once
per call, exactly as the composition's former `{ eventId -> leaveNotifier.leave(eventId, deviceId) }`
closure did.

**Why not five new ports:** a port per lambda would grow `ports/` by 5 for 5 needs that are already
named. The test the law states is whether the *name* survives a second platform, not whether each
call gets its own interface.

### D6 — The `Keychain` family is the same violation, deferred, and named here

`ports/Keychain` is named for Apple technology and carries `migrateAccessibility()` and
`Unavailable(status: Int)` (an `OSStatus`) into the platform-free zone; its types reach
`ConfigPorts`, `AttestSeams`, and `feature/album/AlbumMapMigration`. It is the same law, and it is
held back for two reasons that are not principled but are decisive:

- it touches `KeychainDeviceIdentity`, whose stored value is **written once and never rewritten** —
  a wrong group or key name is frozen permanently on a value whose loss is unrecoverable, and that
  exact failure has happened once already (2026-07-20: two device ids across two processes, and the
  app re-imported every photo it had uploaded);
- the simulator coverage that would make the reshape verifiable does not exist yet.

The reshape should be **value-preserving by construction** — changing only the interface's name and
error type while leaving `kSecAttrAccessGroup`, the item key and the accessibility class
byte-identical — and should land after those three values are pinned explicitly. They were implicit
when the 2026-07-20 incident happened; pinning them is the durable fix independent of any rename.

### D7 — `appVisibilityFrom` is deliberately excluded

It is neither gap. It is not an `AppPorts` field, and it is not the OS calling in — it is the shell
*reading* `UIApplication.applicationState` to decide what to compose, which is arguably wiring
(deciding what to build), except the decision sits in `model/` because the shell may not branch.
It is a third shape, and folding it in would repeat the conflation this change exists to undo.

It is also the site the identifier gate provably cannot catch (D8), so including it would create the
impression the gate covers it.

### D8 — Each gate's blind spot is normative, not a footnote

The identifier gate is lexical, so an ABI decoder written in bare integers is invisible to it: a
`when` over `0L/1L/2L` that is in fact a `UIApplicationState` table reads as arithmetic. Worse, its
hits are **anti-correlated with risk** — it fires on named constants like `isConfigFileAbsence`,
which is *namespaced* (`(domain, code)`) and whose `else -> false` makes an unknown platform defer
safely, and is silent on unnamespaced integer tables, where a second platform's values collide and
produce a wrong answer rather than a safe default.

A green gate read as "the core is neutral" would therefore be actively misleading. The precedent for
handling this is `MainLaneContainmentTest`, whose own documentation says *"it contains a lane; it
does not decide whether a call blocks."* Both new gates state their limit the same way, in the spec
rather than only in the test file.

**Alternative considered and rejected:** a convention that "a decoder over another system's values
must name that system in its identifier" (`appVisibilityFromUIApplicationState`), which would make
the invisible cases lexically visible. Rejected because every known instance moves to an adapter in
this change or is out of scope, so the convention would land with zero live instances — a law
existing only to catch a future repetition of a mistake the structure now prevents.

### D9 — Function-typed fields are constrained, not banned

15 of the 20 function-typed fields in `AppPorts` are legitimate and stay (and all 6 of
`UploadPorts`'). `flow/` may not reference
`ports/` (the flow-no-ports gate), so the composition hands flows their collaborators as lambdas;
the platform touch still goes through a port at the other end. The rule is about *where the chain
terminates*, not about the lambda.

### D10 — A gate row is overturned: `interface LeaveNotifier` comes back

`DeletionLedgerTest` pinned `interface LeaveNotifier` as retired dead weight, with the rationale
*"the class is the seam"* (decision record `changes/archive/2026-07-17-delete-dead-weight`, which
deleted it as single-implementation interface ceremony). This change resurrects the name, and the
guard row is **deleted** rather than narrowed — there is nothing left of that judgement to keep dead.

The row's own contract is what permits this: resurrection is not forbidden forever, it is forbidden
**silently** — bringing an item back means deleting its guard row in the same commit, with the
argument stated. This is the argument.

**A port is not justified by having a second implementation.** That is the test the 2026-07-17
judgement applied, and it is the wrong one: an interface with one implementation is ceremony, but a
**port** is not an interface that abstracts over implementations — it is the declared boundary where
the core stops and an external system begins (this spec, "Ports are the I/O boundary named for the
need"). Its job is to be *visible*, and it does that job with exactly one implementation.

The evidence is what happened next. With the interface gone, the composition still had to get a
`DELETE` from `LeaveEvent` to the backend, so it handed the core a `suspend (eventId) -> Unit`
closure over the adapter and this device's id. Nothing was saved and one thing was lost: the crossing
became invisible to every gate that reads types, and to every reader who does. The ceremony argument
counted the interface's cost (one file, one indirection) against a benefit it had mis-stated, and
never counted the cost of the closure that necessarily replaced it.

Two guards now express that: `MixedPortImplTest` (already standing) keeps a port from cohabiting with
its technology impl, and the seam gate (D1) is what would have caught the closure. Note the ordering
this implies for the next such call — **delete an interface only after asking what will carry the
crossing instead**; "the class is the seam" is true only while nothing but the class is in the way,
and a composition bundle is always in the way.

## Risks / Trade-offs

- **The identifier gate's green is narrower than it reads** → its limit is normative in the spec and
  repeated in the test's own documentation; D8 records why the alternative (a naming convention) was
  rejected rather than forgotten.
- **A pin records a judgement, and a bad judgement passes forever** → pins are exact in both
  directions, so every entry is re-examined whenever the code it describes moves; and each pin
  carries its reason at the site, matching the shell gate's existing convention.
- **The seam gate reads a declaration, not a call graph** → a pinned lambda whose body is later
  rewritten to reach out of the process still passes, and its reason goes stale silently. Accepted:
  the alternative needs type resolution across modules and binaries (D1), and the pin's value is
  that the judgement was made and is readable, not that it is re-proved on every build. Stated in
  the gate's own documentation so a green run is not read as a claim it does not make (D8).
- **Moving tests to `iosTest` narrows them to macOS CI** → accepted, and it is a net gain: the
  assertions being moved are tautological on the JVM (comparing a constant to a copy of itself) and
  become able to fail once they can reference the real platform symbols.
- **`Resource.contentType` changes the stored object's `Content-Type` header** → no consumer reads
  it. Download URLs are presigned, so no third party holds one; the import path branches on the
  device manifest's value, which is already the MIME; the ledger row already prefers the metadata
  MIME. Verified end-to-end against `api/src/app.ts`, which passes the client header through
  unmodified and returns the manifest value in the union.
- **Two new ports and three port members widen `ports/`** → the surface grows by the needs that were
  already being served, and D5 keeps three of five on ports that already exist.
- **The gates cannot land before the sites are fixed** → they are armed in the same change, after
  the moves, so the build is never green over a known violation (Migration Plan).

## Migration Plan

Ordering is forced in two places and otherwise free:

1. **Gap 2 moves first** — the translations and constants go to their adapters, their tests move
   with them and are strengthened to assert against real platform symbols.
2. **Gap 1 moves next** — the two new ports are declared, three existing ports gain a member, the
   five lambdas are deleted from `AppPorts`/`UploadPorts`, and `SnapSyncRoot` wires ports instead.
3. **Both gates are armed last**, in the same change. Arming earlier would require landing pins that
   are really a to-do list — a green build sitting over known violations, which is the failure mode
   the gates exist to prevent.

No runtime migration, no stored-state change, no version gate. Rollback is a revert: nothing here
writes durable state or changes a wire format any consumer reads.

## Open Questions

- **Does the seam gate's scope end where the OS calls in?** The spec draws the line there —
  registering an `NSNotificationCenter` observer or submitting a `BGProcessingTaskRequest` is the
  shell being called, not accessing. That boundary is stated but untested by this change; the first
  site that sits ambiguously across it will decide whether it holds.
- **Should `feature/` be in the identifier gate's scope permanently?** It has exactly one hit today
  (`AlbumMapMigration`, which rides with D6's deferral) and is otherwise clean. Including it costs
  nothing now and prevents the zone becoming the next place translations land.
- **`:ui:presentation` is not scanned.** It is subject to the presentation-imports gate and was not
  part of the audit. Whether the identifier gate should cover it is unresolved.
