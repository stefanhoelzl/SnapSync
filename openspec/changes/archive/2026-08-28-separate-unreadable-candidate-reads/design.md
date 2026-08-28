## Context

`AppCore` derives what the app may read from **one pair of inputs** — the photo-access grant and
`latestSelectionSnapshot`, a `MutableStateFlow<List<Resource>?>` that is `null` until the
photo-selection-change observer's first emission. Four readers consume that pair, in four vocabularies,
and they answer the null snapshot differently:

| reader | null snapshot → | correct? | why |
|---|---|---|---|
| `selectionScope()` (upload) | `Scoped(emptyList())` | **yes** | cursor preserved, no pruning, next emission retries |
| `PermissionAwareCandidateSource` (count, preview) | `emptyList()` | **no** | a counted zero settles a screen that cannot un-settle |
| `PermissionAwareAssetPresence` (download guard) | every id `UNKNOWN` | **yes** | the return type already carries "couldn't tell" |
| `installPermissionSubscriptions`' import sweep | *waits* (`filterNotNull().first()`) | **yes** | one sweep per process, no re-arm |

The third row is not luck. `AssetPresence` has an `UNKNOWN` value, so the absence flows into a type with
the capacity to express it. `List<Candidate>` has no such value — at the element level or the whole-result
level — which is why the identical `?: emptyList()` is safe in one wrapper and a defect in the other.

The consequence is asymmetric because the **status projection cannot retract a settled frame**.
`LedgerBackedSyncStatusSource` publishes only `SyncStatus.Ready` (`sync-status`, *"once `Ready`, a source
MUST NOT regress to `Loading`"*), and `total == 0` with a read ledger is a `Ready`. So a zero standing in
for an unread library does not merely mislead briefly — it ends the neutral state permanently, and the
honest frame that follows reads as the screen going backwards. That is the shape of `SNAPSYNC-14` /
`SNAPSYNC-16`, which `OwnDeviceGalleryStatusSource` closed at its own level by making `size` an `Int?`
while the seam beneath it kept collapsing.

Constraints this design works inside:

- `model/` is the innermost zone and references nothing project-internal outside itself, so `EventPhotoSet`
  cannot name `ports/CandidateSource` and takes a lambda instead (`module-architecture`, "Zones inside the
  core").
- `EventPhotoSetSourceTest` (`:test:architecture`) fails any `:domain` production `EventPhotoSet(…) {`
  lambda-block construction outside a two-entry allowlist, because that seam previously existed with the
  same signature and all nine call sites ignored their policy parameter.
- **Nothing mechanical covers this collapse.** The guard that derived a population from *nullable*-returning
  `ports/` members would not have seen it anyway — `emptyList()` is outside that population — and the guard
  audit has since retired it as unearned. At this seam the absence law is prose discipline, so the shape has
  to be carried by the type rather than by a gate.
- `gallery-status` ("Library resource enumeration seam") forbids a second enumeration port layered over
  or beneath the one seam.

## Goals / Non-Goals

**Goals:**

- Make `CandidateSource` distinguish "the admitted set cannot be stated" from "the admitted set is empty",
  at the `ports/` boundary where the absence law is enforceable.
- Close the reachable `LIMITED` pre-snapshot defect, with an integration test that fails before the fix.
- Delete both compensating grant checks, and amend the spec clause that assigned them to consumers.
- Keep the `EventPhotoSetSourceTest` guard's meaning and coverage exactly as they are.
- Make the upload arm's cursor preservation a stated requirement, since it is what makes the *other*
  `?: emptyList()` safe.

**Non-Goals:**

- **Unifying the four readers onto one `SelectionScope`-shaped input type.** Tempting — the type exists,
  one case short — and it would have caught this at compile time. But the four readers *should* disagree
  about the null snapshot (see the Context table): a shared type would give them a shared input
  vocabulary, never a shared decision. Adding a case to `SelectionScope` forces four readers to restate
  themselves, which is a review of four consequences across three features and the upload core — its own
  change, with its own design. Recorded here so the next reader knows the divergence is deliberate and
  does not "fix" it by symmetry.
- **Touching `PermissionAwareAssetPresence`.** It is correct today, and the failure it guards is worse
  than the one being fixed: `photo-download` records that a wrong `ABSENT` clears a live marker, imports a
  second copy, and orphans the first. Refactoring a correct download guard inside a status bug fix is a
  bad trade.
- **Modelling "the platform walk threw" in the read type.** A thrown enumeration is a genuine failure,
  caught inside `OwnDeviceGalleryStatusSource`; putting it in the seam would make the read carry the
  platform's faults. Assigned elsewhere.
- Moving `refreshStatusSources`' ordering rule into the `Foreground` flow. Assigned elsewhere; it moves
  the same lines this change edits.
- **Re-arming a mechanical check for empty-collection collapses.** The nullable-seam guard that would have
  been the place to extend has been retired, so this would mean proposing a new gate rather than widening
  one — a separate argument, and one that has to earn itself against the audit that just removed its
  predecessor.

## Decisions

### D1 — A sealed return, not a `Result`

`Result` was considered and refused on three grounds, recorded so it is not re-opened:

1. It puts a **non-error in the error channel**. `DENIED` is not a failure; the read succeeded and the
   answer is "you may not have one". `Result.failure` needs a `Throwable`, so it would mean manufacturing
   an exception to carry a permission state — against the project rule that errors are reduced into state.
2. There are **three answers, not two**: a list (possibly empty — *nothing qualifies*), *not readable*,
   and *couldn't look* (a thrown platform walk). `Result` has two channels for three answers, so two fold
   into `failure` and are told apart by exception type — a sealed hierarchy in disguise, with no
   exhaustiveness.
3. `Result` is not exhaustive. `getOrNull()` / `getOrElse {}` are the same silent default that produced
   the defect. The seam already leans on sealed-ness for `SelectionPolicy` for exactly this reason.

The third answer is deliberately kept **out** of the seam (see Non-Goals).

### D2 — `NotReadable` is named for its consequence, not its cause

The obvious framing — *"no usable photo grant"* — names the cause and would scope the case to `DENIED` and
`NOT_DETERMINED`. The `LIMITED`-before-snapshot case would then stay in `Readable(emptyList())`, which is
the only cause reachable on a shipped device. So the case answers **"can I state this membership's
admitted set right now?"** and absorbs three causes:

```
                    Readable(candidates)              NotReadable
                    ────────────────────              ───────────
  GRANTED           the bounded library walk
  LIMITED           the snapshot, which HAS arrived
  LIMITED (pending) ─────────────────────────────▶    snapshot not yet emitted
  DENIED            ─────────────────────────────▶    no grant
  NOT_DETERMINED    ─────────────────────────────▶    grant unresolved
```

Collapsing the three is legitimate under the absence law's own test — *consequence asymmetry, not
nullability*. No consumer distinguishes them: the total goes unknown and the preview renders no row in all
three. The shared consequence is stated, which is what the law requires. If a device log needs the cause,
`SecureStoreRead.Unavailable`'s precedent applies: an opaque diagnostic no caller may branch on.

### D3 — The unwrap lives once, behind a private constructor

`EventPhotoSet` keeps its public policy-taking lambda constructor and gains:

- a **private** secondary constructor over candidates already in hand, and
- a companion function performing the one `CandidateRead` `when`, returning `EventPhotoSet?`.

Consumers read `EventPhotoSet.<unwrap>(policy, source::candidates)?.count()` — the method-reference shape
the guard wants, with the policy genuinely reaching the platform on the line that matters.

Alternatives rejected:

- **Lambda returns `CandidateRead`.** Makes `count()`/`assets()`/`resources()` nullable so that
  `UploadCycle` and `DeviceManifest` can immediately un-null them with a default — reintroducing the silent
  collapse at the upload path and at a projection over ledger rows where no library read exists. Widening a
  total answer into a partial one for a case that cannot occur is the wrong direction.
- **Unwrap at each consumer.** The two count consumers become lambda constructions and trip the guard. The
  allowlist entries would be *true* (the candidates genuinely are in hand), which is the tell: it satisfies
  the guard's intent while failing its proxy, i.e. it edits the guard to fit the code. The two sites added
  would be the status total and the join preview — two of the nine the guard was written to remember.
- **A public or `internal` list-taking constructor.** Also dodges the guard's regex, and is much worse:
  today the only way to build from an in-hand list is a lambda block, which is the shape the guard can see.
  An open list constructor makes the eager, policy-ignoring construction invisible **everywhere** while
  turning the check green. `internal` does not help — `model/` is in `:domain`, so both existing allowlisted
  sites could reach it. It has to be `private`.

Converting a sealed type into a nullable one line later is not a retreat: the null has one cause, one
producer, and two consumers that each map it to their own stated absence. The absence law says so directly
— *"A nullable seam is not automatically a violation."* The sealed type earns its keep at the `ports/`
boundary, where three causes converge and the law is enforced.

### D4 — `refresh` never publishes a count it did not compute, nor withdraws one it did

Under `NotReadable`, `OwnDeviceGalleryStatusSource.refresh` logs and writes nothing to `_size`.

"Leave the previous value" and "set it to `null`" are **observationally identical** here, because the
projection publishes only `Ready`: nulling makes `combine` yield `Loading`, which is never published, so
the screen holds its last `Ready` frame either way. The choice is therefore made on consistency, and
`gallery-status` already fixes it for the neighbouring case — *"An enumeration that **fails** SHALL leave
the previous value in place… and SHALL NOT publish a count it did not compute."* A refusal must not be more
destructive than a failure.

Severity is `Warn`, not `Error`: `Error` reaches Bugsink (`crash-reporting`), and a member who denied photo
access is not a defect.

### D5 — Call through; the log line is the point

`refreshStatusSources` drops its `if (grantsPhotoAccess)` and always calls `gallery.refresh`. Today a
denied grant produces no `gallery:` line at all, so a device log cannot separate "never refreshed" from
"refused" — which the absence law's *An entry point declines to act* scenario addresses directly, and which
the iOS shell already argues for the rig ("must ask for access before fetching or its empty result is
indistinguishable from an empty library").

The cost is that the policy must be derived **before** the source can answer, and `selectionRulesFor` reads
its two exclusion sets eagerly. That is small on the status path (joined device, once per foreground) and
material on the **join surface**, where `ShareableCountSource` runs, `NOT_DETERMINED` is the normal state,
and the denylist lookup is **not** memoized despite its KDoc's claim.

### D6 — The denylisted-album reader is asked only when the library can be read

**Measured, and it is load-bearing.** Simulator, iOS 26.4, 2026-08-28: a `PHAssetCollection` fetch under
`NOT_DETERMINED` issues a **non-preflight** TCC request and iOS **presents the photo-permission dialog** —
`tccd` logs one `AUTHREQ_PROMPTING` for the app. The A/B that pins the cause, both arms starting from a
cleared alert and differing only in participation direction:

| joined membership | album fetch reached? | `AUTHREQ_PROMPTING` |
|---|---|---|
| `DownloadOnly` — resolves to `DenyAll` before either exclusion reader is called | no | **0** |
| `UploadOnly` — calls `albumExcludedAssetIds` | yes | **1** (`preflight=no`) |

This matters *because* of D5. The consumers no longer gate on the grant, so the policy must be derived
**before** the read seam can report that it has no answer, and `selectionRulesFor` reads its two exclusion
sets eagerly. Without this decision, `refreshStatusSources` would raise an unrequested system dialog on
**every foreground** of a joined device whose grant is undetermined — a state a member reaches by resetting
privacy settings, and one the app must never answer with a prompt it did not ask for. The app has its own
"Allow photo access" affordance for that conversation.

The reader is therefore wrapped in `compose/` (`albumExclusionsWhenReadable`), the third permission-aware
seam in that file and the same shape as `PermissionAwareCandidateSource` and `PermissionAwareAssetPresence`:
choosing behaviour by a port's state is composition. `AlbumCoordinator` already gates its own writes the
same way. The empty set is the honest answer rather than a fallback — the denylist is a subtraction and the
policy admits on doubt, which is why `limited-photo-access` records the denylist as inert under a partial
grant.

**Rejected placements, both attempted or considered:**

- **The iOS shell** (`SnapSyncRoot.albumExcludedAssetIds`). Tried first; failed `detektAppShell`, whose
  threshold of **2** is a *proof* that `:app:*` Kotlin holds no decisions (`module-architecture`, "Shells
  are wiring only"). The gate was right and the placement was wrong.
- **The album adapter** (`IosAlbumManager`, `:adapter:ios:ext-safe`). It cannot see the grant, and it is
  also linked by the extension, so the grant would have to be threaded in as an injected fact through both
  composition roots — more machinery than the composition-level wrapper, for the same outcome.

**Verified after the fix**, same host and same state (`UploadOnly` + `NOT_DETERMINED`):
`AUTHREQ_PROMPTING = 0`, no dialog, and the screen shows the app's own "Allow photo access" affordance
while `gallery: library not readable — N stays unknown` confirms D4 running on a device.

### D7 — The upload arm's identical collapse stays, and gains a requirement

`selectionScope()`'s `?: emptyList()` is safe, and for reasons nothing currently records:
`SelectionScopedTransfer` sets `nextToken = sinceToken` (the cursor never advances past unseen assets),
`fullEnumeration = false` (no ledger pruning), and the next observer emission re-triggers the cycle. One
idle cycle, self-healing.

`SelectionScopedTransferTest` does pin the cursor — but both its comment and the class KDoc give the
**efficiency** reason ("a later full-access walk resumes incrementally"), not the correctness one. A test
that pins the right value for the weaker reason is a test someone can talk themselves past: "we are leaving
performance on the table across the whole limited period" is a plausible pitch, and advancing the cursor
would step the change feed permanently past photos nobody enumerated. Hence the requirement.

## Risks / Trade-offs

- **A `PHAssetCollection` fetch under `NOT_DETERMINED` surfaces a system prompt** — measured, not
  hypothetical → D6 gates the reader in `compose/`, and the fix is verified on the same host. Expiry
  trigger: re-measure at the next iOS major, or if Apple documents the fetch-time authorization behaviour.
  Evidence caveats: one simulator runtime (iOS 26.4), one device model, n = 1 per arm.
- **Two of three implementations can never answer `NotReadable`**, so `PhotoKitCandidateSource`,
  `InMemoryCandidateSource` and their callers gain branches that cannot fire → accepted. The alternative —
  putting the sealed type on a narrower seam only the app-side consumers hold — is refused by
  `gallery-status`, "Library resource enumeration seam" (*"there SHALL NOT be a second enumeration port
  layered over or beneath it"*).
- **A wide mechanical diff hides a bug fix** → the failing integration test lands first and alone, so the
  defect is legible in its own commit before the churn arrives.
- **Line-level collision with two adjacent changes in another workspace** (moving the enumeration catch into
  `OwnDeviceGalleryStatusSource`; moving `refreshStatusSources`' ordering into the `Foreground` flow) → both
  edit `SnapSyncApp.kt:716–756` and `OwnDeviceGalleryStatusSource.refresh`. The `if` deleted here is the
  direct parent of the `runCatching` they move. Preferred order: their two land first, which leaves this
  change a bare `gallery.refresh(...)` call and puts the `NotReadable` log beside the failure log rather
  than competing for the same site.
- **`permission-gate` and `limited-photo-access` disagree** about whether the process survives a Settings
  grant change → noted, not resolved. D4's behaviour is identical under either reading, since the
  projection cannot regress to `Loading`.
- **Behaviour delta beyond the two deleted checks is genuinely small but non-zero**: a live
  `GRANTED → DENIED` transition now produces a log line and no count write, where before it produced
  neither. No frame changes.

## Migration Plan

No data migration, no persisted format change, no backend change. Sequence:

1. The red integration test (`LIMITED`, provisioned, `refreshStatus()` before any snapshot emission →
   asserts the host settles to `InSync`). This is the regression proof and the `bug` label's evidence.
   **Confirmed both ways**: it fails before the fix (`expected null, but was <0>`) and fails again when the
   `NotReadable` branch is reverted to `.orEmpty()`.
2. `CandidateRead` + `EventPhotoSet`'s private constructor and companion unwrap, with `commonTest` coverage
   on JVM and the simulator.
3. The three implementations, then the two consumers, then the mechanical unwraps.
4. The three delta specs.

Verified with `./gradlew build` (canonical, no display) and `./gradlew compileIosMainKotlinMetadata` (the
Linux-runnable proxy for the `iosMain` source sets — roughly a third of the touched files).

Rollback is a revert: nothing outside the process observes the seam.

## Open Questions

- ~~Does a `PHAssetCollection` fetch under `NOT_DETERMINED` surface a system permission prompt?~~
  **ANSWERED: yes.** Measured on a simulator (iOS 26.4, 2026-08-28) — see D6 for the A/B and the fix.

  Recorded because the reasoning that preceded it was **wrong, and wrong in an instructive way**. An
  earlier pass argued the question away from the call graph: `ShareCountRow` is the only caller of the
  join preview, it composes only on `JoinPhase.Detailed.Step.Ready`, and `deriveLoadedPhase` sends a first
  join at `NOT_DETERMINED` to `ExplainAccess` instead — so any prompt would already be on screen, raised
  by the app's own `requestAccess()`. Every step of that is true, and it misses the case that matters:
  `refreshStatusSources` derives the same policy on **every foreground** of any joined, upload-inclusive
  membership, with no join surface involved at all. A call-graph argument that enumerates one consumer of
  a shared derivation is not an argument about the derivation.
- One pre-existing defect found and repaired in passing: two scenarios in
  `openspec/specs/limited-photo-access/spec.md` ("The snapshot is read at the sanctioned points only", "No
  consumer branches on the grant to pick a source") ended **mid-sentence** in the main spec, with no blank
  line between them — the signature of a truncated MODIFIED delta at an earlier archive. A MODIFIED
  requirement restates the whole block, so this change's delta completes both from the surrounding
  requirement text. Worth confirming the completions read as intended.
- Should the `NotReadable` log severity differ by cause — `Warn` for a grant the member chose, `Error` for a
  selection snapshot that never arrived (a broken port, arguably Bugsink-worthy)? D2 keeps the causes
  indistinguishable to callers; a diagnostic string could still separate them in the log without giving
  anyone something to branch on.
