## Context

Change 3 of the testing-concept sequence. Its brief (written by the `testing-concept` workspace's agent)
asked for a *probe → contract → mock → real-adapter* loop proven on one small external system, including
"a generator: a probe run emits a contract with a provenance header". Two implementations were built and
reverted (tags `wip/port-contracts-v1`, `wip/probe-derived-contracts-v2`); this design was re-derived from
scratch in an operator interview, and keeps only the measured facts those attempts produced.

Current state:

- **One contract shape exists and works.** `LedgerStoreContract` / `DownloadStoreContract` (`:test:world`
  `commonMain`) are `abstract class` + `@Test` + `createBackend()`, bound by SQLDelight (jvm), the native
  driver (iOS sim) and both fakes. They have no notion of state or host.
- **No honest `SecureStore` fake exists.** Only test-local doubles: `StubSecureStore` (ext-safe `iosTest`)
  and a private `FakeSecureStore` (`:domain:ports` `commonTest`).
- **`SecureStore` is implemented three ways:** `IosKeychain` (device and kexe), `AppGroupFileSecureStore`
  (simulator target's device-id store), `NoSuchStore` (simulator legacy store; always `Absent`, `write`
  throws `IllegalStateException`).
- **The host is part of the fact.** Measured: the same `SecItem*` calls answer `-25291`
  (`errSecNotAvailable`) from a Kotlin/Native `test.kexe` spawned by `simctl`, and `-34018`
  (`errSecMissingEntitlement`) from an unhosted Swift `.xctest`. Conflating them nearly shipped a false
  "correction" to `IosKeychainTest`'s KDoc. `TEST_HOST` cannot host a KMP test binary, so an entitled
  Keychain is reachable only from the app itself.
- **Kotlin/Native has no reflection**, so an in-app runner cannot discover `@Test` methods, and
  `kotlin.test` has no dynamic skip.
- **Merging is not operator approval here**: the `main` ruleset requires zero approving reviews, `/ship`
  auto-merges, and agents open PRs under the operator's account.

## Goals / Non-Goals

**Goals:**
- A contract per port, run unchanged against the fake and every real implementation, on every host each
  can reach, with every non-run stated and none silent.
- Evidence from hosts CI cannot reach (the entitled device) that **every build** re-judges against the
  current clauses and the current adapter code.
- One contract shape and one runner in the repository.
- Proven end-to-end on `SecureStore`.

**Non-Goals:**
- Generating contract content from observation (see D1).
- Contracts for any port other than `SecureStore`, `LedgerStore`, `DownloadStore`.
- Implementing an OS-scheduled system (push, BGTask, background `URLSession`, the PhotoKit extension).
  The outcome vocabulary expresses them (`NotWithin(T)`); none is bound here.
- Binding `NoSuchStore`, the Swift shell, or the app on the simulator (the last is measured once, D10).
- Specifying clauses in `openspec/` (D2).

## Decisions

### D1. No generator: contracts are hand-written; "probing" is running the contract against the real thing

A contract clause executed against a real implementation cannot state a falsehood and keep passing, so
recording expectations from reality buys discovery at the cost of a generator, a format, matchers for
volatile values, and — decisively — the ability to record an adapter bug as truth (a reintroduced
build-297 would be recorded as `Absent` and the fake then held to it). Hand-written clauses are always
someone's stated intent. Discovery still works: a wrong guess fails with `expected X, actual Y`.

This departs from the brief's "a probe run emits a contract". The proposal states the departure; it must
not be read as satisfied.

*Alternatives:* a probe recording raw system facts plus a separate authored contract (two mechanisms,
nothing linking a clause to an observation); one scenario set in RECORD/VERIFY modes with recorded
expectations and protected authored ones (a generator plus the recorded-bug hazard, contained only by
review).

### D2. The code is the specification of a port's clauses

A contract's clause list in `:test:contracts` is the single artifact that states and enforces a port's
obligations. This is `openspec/config.yaml`'s "what earns a spec" rule applied: where one committed
artifact *is* the contract, a spec restating it is a second copy with no gate behind it. `port-contracts`
specifies the **mechanism**; no capability gains a requirement listing `SecureStore` clauses.

*Alternatives:* clauses as requirements in the capability owning the port's meaning (`device-identity`
for `SecureStore`); one requirement per contract inside `port-contracts`.

### D3. Clauses are conditioned on states; bindings enter them at construction

A clause names the **state** it needs, from a hand-written per-port vocabulary that lives beside the
contract (e.g. `SecureStoreState = Inaccessible | Empty | Holding(value, protection)`); production code
does not gain it. A **binding** is one (implementation, host) pair providing `create(state)`: a fresh
implementation already in that state, or `Unreachable(reason)`. Every clause gets a fresh instance.

Construction-time entry fits both constraints the tree imposes: `FakeHonestyTest` admits state only
through a fake's constructor, and real implementations cannot change state on command (a kexe
`IosKeychain` is inaccessible for its whole life). A scenario needing a transition mid-run ("unavailable,
then recovers") is not a contract clause — no real host drives it — but a fake-backed test of our own
logic (`DeviceIdentityRetryTest` already is one).

Each binding also declares, as a literal, which states it reaches, so the coverage gate can read it
statically (D7). At run time the runner checks `create` agrees with the declaration: a declared state
answered `Unreachable`, or an undeclared one answered `Ready`, is `Failed`.

*Alternatives:* levers transitioning a live instance (only fakes can, which D6 would then reject as
fake-only in practice).

### D4. Contracts are clause values, repository-wide

The device runs clauses in-app, with no test runner and no reflection, so a contract must be an explicit
list. On CI one `@Test` per (contract, binding) runs the whole list, collects every outcome, and fails
**once** with the full outcome table — better than stopping at the first failure. Clause bodies use
ordinary `kotlin.test` assertions and may suspend.

`LedgerStoreContract` and `DownloadStoreContract` are converted and moved in this change, so there is one
shape and one runner. Their clauses need no state distinction today; they use a single `Empty` state.

*Alternatives:* keep `abstract class` + `@Test` and add a hand-kept list for the device (two lists that
must agree — a clause missing from the device list silently never runs there — so a guard just to stay
honest, plus the `NotRunHere` machinery `kotlin.test` lacks); values for new contracts only (two shapes
coexisting).

### D5. Outcomes, and why each is distinct

| outcome | meaning | action |
|---|---|---|
| `Passed` | the clause held | — |
| `Failed(msg)` | the implementation (or recorded hardware answer) violates the clause | fix code or clause |
| `NotRunHere(reason)` | this binding cannot reach the clause's state | none, if another real host covers it (D6) |
| `Diverged(msg)` | replay: the clause made an OS call the recording lacks | re-record |
| `NotWithin(T)` | a bounded wait on an OS callback expired | expressible; unused in this change |

A CI `@Test` fails on any `Failed` or `Diverged`. `NotRunHere` never fails a run by itself; the coverage
gate decides whether it is admissible.

### D6. Every clause runs against at least one real implementation on at least one host

A clause no real implementation reaches anywhere looks verified while nothing ever compared it with
reality. It is always one of three other things: a fact about the platform ("before first unlock iOS
makes the Keychain inaccessible" → adapter documentation, with evidence), a clause whose state some host
*does* reach ("when inaccessible, `read()` is `Unavailable`" → the kexe host reaches it), or a test of our
own logic over the port (→ an ordinary fake-backed test). So there are **no fake-only clauses**, and no
approval mechanism is needed for them.

The rule also closes the escape hatch: declaring a failing clause's state unreachable on its only real
host leaves the clause with no real host, and the build goes red.

*Alternatives:* fake-only clauses justified inline and/or in a central register, with operator approval
(approval cannot be made mechanical here — merges are not approvals — and approving "fake-only" would
miss the general escape hatch, which is any new `NotRunHere` on a host that used to run a clause).

### D7. The contract-coverage gate derives the rule statically

A `:test:architecture` gate reads, from source text (`SourceScan` reaches `iosTest` and the rig source
set) and from the committed recordings:

- every contract's clause ids and the state each needs;
- every binding's host, kind (`Fake` / `Live` / `Replay`) and declared reachable states;
- every recording's host and the clause ids it holds a block for.

A clause is covered when a `Live` binding declares its state reachable, or a `Replay` binding's recording
holds a block for it (on replay, "reachable" means "recorded"). An uncovered clause fails, naming it. The
gate also fails a `Host` value no binding names. Per "Gates fail closed on novelty" its scope is derived,
never listed, and it keeps a non-vacuity twin per derived group (contracts, bindings, recordings).
Declarations are required in a literal form; a form the gate cannot read fails loudly rather than being
skipped.

### D8. Hosts: a closed enum of what changes reachable states

`Host` = platform × process kind × entitlements, because that is what changes which states a binding can
reach and it is known statically. OS version, device model, toolchain and date are **provenance** — an
iOS update that changes an answer is a *failure on the same host*, with history, not a new host without
any. The enum holds only hosts that have a binding: `JVM`, `IOS_SIM_KEXE`, `IOS_DEVICE_APP`. The name
`IOS_SIM_KEXE` is deliberate: the simulator's K/N test executable and a Swift `.xctest` are different
hosts that give different answers.

The known matrix (JVM; sim kexe `-25291`; sim xctest `-34018`; sim app — unmeasured; device app; device
extension — later) is written into `port-contracts` so the next binding starts from it.

### D9. Record at the OS boundary; replay the current adapter

For a host CI cannot reach, a device run records the calls the adapter makes **to the operating system**
and the OS's answers — not the port calls. Recording at the port would record the *adapter's* answers,
and replay would then test the old adapter forever: a reintroduced build-297 would replay green, and only
a source-hash guard (forcing a re-record on a comment edit) could compensate.

At the OS boundary, CI runs the **current** `IosKeychain` against iOS's recorded answers:

- a comment or refactor makes identical OS calls → replays green, nothing to re-record;
- a behaviour change sends iOS something unrecorded → `Diverged` → re-record on the device;
- identical calls whose recorded answer violates a clause → `Failed` → a real finding.

This needs an **internal seam** in `IosKeychain` over its four direct `SecItem*` calls
(`SecItemAdd` / `CopyMatching` / `Update` / `Delete`): the real implementation stays in
`:adapter:ios:ext-safe` (Keychain containment unchanged); the recorder and the replayer implement the same
seam. State seeding (e.g. a legacy item filed under `kSecAttrAccessibleWhenUnlocked`) goes through the
seam too, so it is recorded and replayed like any other call. Replay runs on `IOS_SIM_KEXE`, because the
adapter is Kotlin/Native over CF dictionaries. Every future replayable adapter needs such a seam; the HTTP
clients already have one (Ktor `MockEngine`).

A replay binding reports its **recorded** host (`IOS_DEVICE_APP`), not the host it executes on.

### D10. Matching, normalisation, determinism

- Requests match **exactly and in order** within a clause. Order is behaviour: `resolveOrMint`'s KDoc
  calls its ordering normative, and ordered replay turns that sentence into a check.
- Answers are recorded **in full**, with a named list of volatile keys (`cdat`, `mdat`, `sha1`,
  `persistref`, extended when a re-record shows noise) masked to fixed placeholders. Filtering answers to
  the keys the adapter reads today was rejected: a key the adapter later starts reading would be silently
  absent on replay.
- Clause inputs are **deterministic**: fixed values, a fixed id generator passed to `resolveOrMint`, and
  service/account names derived from the clause id.
- A missing recording file, or a recording lacking a block for a clause the device binding declares
  reachable, is `Failed` at replay — never a quiet `NotRunHere`.

### D11. The recording file

Plain, line-oriented text at `test/contracts/recordings/<Contract>@<HOST>.rec`: a provenance header
(contract, host, device, iOS version, build number, Kotlin version, date) then one `[CLAUSE_ID]` block per
clause, sorted by id, of `call -> answer` lines with no alignment padding. One file per (contract, host),
**overwritten** by each run — `git log` is the history. The rig returns the file content verbatim; it is
committed unedited and reviewed as a diff. Recordings are input, never expectation.

*Alternatives:* JSON (noisy diffs); an append-only log (history in-file, every diff a pure append).

### D12. Module placement

| piece | home | why |
|---|---|---|
| mechanism, all contracts, state types | new `:test:contracts` `commonMain` (jvm, iosSimulatorArm64, iosArm64) | a test source set cannot be depended on or linked; the device app must link it |
| fake bindings | `:adapter:generic:fake` `commonTest` | the fakes are `internal`; their own test set can construct them with state |
| SQLDelight / native bindings | `:adapter:generic:app` `jvmTest` / `iosSimulatorArm64Test` | where they are today |
| kexe live + replay bindings | `:adapter:ios:ext-safe` `iosTest` | `internal` access to the seam |
| `AppGroupFileSecureStore` binding | `:adapter:ios:ext-safe` `iosSimulatorArm64Test` | the store is `internal` to that target |
| device binding + recorder | `:adapter:ios:ext-safe` rig-gated source set | must be non-test and app-linked; keeps the seam `internal` |
| `/contract/<name>` | `:test:rig`, fed lambdas from `:app:ios`'s rig hook | `:test:rig` names no platform API, by design |
| coverage gate | `:test:architecture` | reads the repository's text |

`:test:contracts` is a **contained** module (`module-architecture`): it links into the app only under
`-Psnapsync.rig=true`. Its withholding argument: once the storage contracts move out of `:test:world`, it
is the only module whose **main** source set depends on `kotlin-test` — assertions in main code are
exactly what a production module must never hold. `:test:world` drops its `commonMain` `kotlin-test`
dependency.

The rig-gated source set inside `:adapter:ios:ext-safe` is a new containment shape — the law so far
admitted contributed call sites only in *shells* — so `module-architecture`'s containment requirement is
amended to admit it, with the same all-or-nothing guarantee. Because `:adapter:ios:ext-safe` is linked by
the upload extension as well, a rig build's extension also carries the device binding; it is inert there
(nothing calls it) and absent from every production build.

*Alternatives:* the device binding in `:test:contracts`' `iosMain` depending on the adapters (the seam
becomes public API of a shipping module; one adapter dependency per bound system); `:test:rig` (breaks its
no-platform-API, no-spec posture).

### D13. `SecureStore` scope in this change

| binding | host | kind | reaches |
|---|---|---|---|
| `InMemorySecureStore` (new) | `JVM`, `IOS_SIM_KEXE` | Fake | all states |
| `IosKeychain` | `IOS_SIM_KEXE` | Live | `Inaccessible` |
| `AppGroupFileSecureStore` | `IOS_SIM_KEXE` | Live | `Empty`, `Holding(_, BACKGROUND_READABLE)` |
| `IosKeychain` (entitled) | `IOS_DEVICE_APP` | Replay in CI, recorded on device | `Empty`, `Holding(_, any)` |

Initial clauses (the code is authoritative once written): an inaccessible read is `Unavailable` with a
diagnostic, never `Absent`; an inaccessible write refuses with `SecureStoreUnavailable` and leaves
nothing; an empty read is `Absent`; write-then-read is `Found(value, BACKGROUND_READABLE)`; a write
replaces; delete of an absent item is a no-op; delete removes; `migrateProtection` preserves the value
byte for byte and yields `BACKGROUND_READABLE`; `resolveOrMint` never mints when inaccessible, and mints
exactly once when empty. `migrateProtection` from `RESTRICTED` has exactly one real host — the device
recording — which is what makes that recording required rather than optional.

The app on the simulator is **measured once** during implementation (what its Keychain answers, given
`simulator.entitlements` omits `keychain-access-groups`) before deciding whether it earns a binding in a
later change.

## Risks / Trade-offs

- **[Replay reads a committed file from a simulator test binary — unverified]** → resolved during
  implementation by embedding rather than measuring: a Gradle task turns each committed `.rec` into a
  generated Kotlin constant for the replay test source set, so the simulator never needs a repository path.
  The `.rec` stays the single source; nothing is copied by hand.
- **[Ordered exact matching over-fits]** — a harmless reordering reads `Diverged` → a re-record is cheap
  and the diff shows exactly what moved; ordering is behaviour for this port.
- **[An iOS update changes an answer and nobody re-records]** → the header shows the iOS version; the
  recording stays authoritative for the host it was taken on until someone re-measures. Age and toolchain
  pins were rejected: they redden builds with no change, and CI cannot see the phone's iOS version anyway.
- **[Volatile keys missed]** → the first re-record diff shows them; extend the masked list.
- **[A binding's reach declaration lies]** → the runner checks `create` against the declaration on every
  run on that host (D3).
- **[Converting two working contracts risks regressions]** → convert clause-for-clause, one commit per
  contract, with the four bindings' outcome tables compared before and after.
- **[A rig build's extension links the device binding]** → inert, rig-only; recorded in D12.

## Migration Plan

Additive except the storage-contract move. Order: `:test:contracts` mechanism → convert the storage
contracts and repoint their bindings (green before continuing) → `SecureStore` contract, fake, live
bindings → the seam → recorder, rig verb, first device recording → replay binding → coverage gate →
measure the simulator app. Rollback is a revert; no production behaviour changes (the seam is
behaviour-preserving, and every other addition is test-only or rig-only).

## Open Questions

- Whether the app on the simulator earns a binding — measured during implementation, decided later.
