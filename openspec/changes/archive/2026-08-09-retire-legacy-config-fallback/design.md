## Context

Migration step 11a (`74d2b848`, 2026-07-18) moved the membership's storage of record from a Keychain
item to a versioned-envelope file in the App-Group container, and kept a **read-only
legacy-Keychain fallback** behind the read: a definitively-missing file consults the pre-11a item,
answers `Joined` if it is there, and writes the found membership forward into the file. The finale
(`94f0bfe5`, step 13b, 2026-07-19) ended the *write*-through — saves and clears became file-only —
and left the read fallback standing, with the reason recorded as contract in
`event-rejoin-reconciliation`'s staging requirement: the migration branch reached `main` as **one
merge**, so at update time the entire joined installed base was pre-11a with no file, and a
fallback-less read would have logged all of it out at once.

The same requirement names its successor and its gate: after *"a production soak — every active
joined device has executed at least one read on a ≥13b build"*, a follow-up change deletes the
fallback and retires the config pair's runtime-identity pin, carrying its own delta to collapse the
staging. This is that change.

What the fallback still buys, and what it costs, are both small and both real. It buys: a pre-11a
device that has been dormant since 2026-07-18 still resurrects. It costs: a **reinstall resurrects a
membership the user deleted the app to be rid of**; a *switch* leaves a stale legacy item, so what a
reinstall resurrects can be the **previous** event rather than the current one (the accepted Stage-1
divergence on record in `event-link`); every leave must delete a Keychain item; and one Keychain seat
plus two `:test:architecture` pins exist to service code that no longer serves anyone.

## Goals / Non-Goals

**Goals:**

- Delete `KeychainConfigReader` in full — read, accessibility repair, and the leave-path delete — so
  no production code addresses the `app.snapsync.config`/`eventconfig` item.
- Make `ConfigFileRead.Missing` mean **definitively not joined**, consulting nothing.
- Delete `configReadFrom`, which becomes dead the moment its one caller goes.
- Retire the config pair's runtime-identity pin (both inventories it appears in), as the staging
  requirement instructs.
- Collapse the staging in `event-rejoin-reconciliation`, and **write down the safety net this
  removes** — see D2, which is the part of this change that is not a deletion.

**Non-Goals:**

- **Changing `isConfigFileAbsence`.** Its classification is unchanged, by decision (D2). This change
  raises what rests on it; touching its logic in the same change would make a behavioural regression
  indistinguishable from the intended one.
- **Purging the orphaned legacy item** from already-migrated devices (D3).
- **A stronger reinstall detector.** The staging requirement forbids one meanwhile, and once the
  fallback is gone there is nothing left for it to do: an App-Group file dies with the install, which
  *is* the detector.
- Any change to the envelope, the file path, the App Group, the ledger, or any wire contract.

## Decisions

### D1 — The Stage-2 gate is fired on a distribution argument, and this is the evidence

The spec's gate is *"every active joined device has executed at least one read on a ≥13b build"*.
There is no telemetry that can report that, and there never will be — SnapSync has no accounts and no
analytics. The gate is therefore discharged by **distribution**: showing that no device carrying a
pre-11a build can be a public user at all.

Verified in this repository at `ce1f75c3`:

| Fact | Verification |
| --- | --- |
| The fallback shipped in `74d2b848` — *"migration step 11a — config → App-Group file, Keychain write-through"* | `git log -1 74d2b848`, author date **2026-07-18** |
| The finale that ended the write-through is `94f0bfe5` (step 13b) | `git log -1 94f0bfe5`, author date **2026-07-19** |
| `74d2b848` is an ancestor of the `v0.1` tag | `git merge-base --is-ancestor 74d2b848 v0.1` → exit 0 (**true**) |
| `94f0bfe5` is an ancestor of the `v0.1` tag | `git merge-base --is-ancestor 94f0bfe5 v0.1` → exit 0 (**true**) |
| `v0.1` is the **first** App Store release | `git tag -l 'v*'` → `v0.1`, `v0.2` only; `v0.1` tagged **2026-07-21** |

Note the second row: the argument satisfies the gate's **literal** wording, not merely a weaker
paraphrase of it. `v0.1` — the first build any public user could ever install — already carried
13b, so *every* App Store install of SnapSync, ever, has been a ≥13b build.

Two properties make the conclusion stronger than "most devices have probably read by now":

- **The migrating read is in a constructor, not behind a user action.** `FileBackedConfigStore`
  seeds its `StateFlow` by reading at construction (a grandfathered exemption in
  `ConstructorBlockingTest`, recorded there with its reason), and both composition roots construct it
  — `SnapSyncRoot` in the app and `UploadExtensionRoot` in the extension. So **any process start of a
  ≥11a build migrates**, including an OS-scheduled extension invocation on a device whose owner has
  not opened the app. The soak does not depend on user behaviour.
- **The property the gate needs is established at 11a, not 13b.** What matters is that the membership
  reached the file, and 11a is what introduced both the file and the migrate-forward read. 13b is the
  strictly later, stricter bound, and it holds anyway.

The residual population is therefore exactly: **internal TestFlight testers who installed a build
from before 2026-07-18 and have not started the app — or had the extension scheduled — since.**
Internal TestFlight is effectively the developer (root `CLAUDE.md`: the internal `development` group
is "effectively just the developer"; the external alpha channel was removed, decision record
`changes/archive/2026-07-19-remove-alpha-testflight-promotion`). A member of that population loses
its membership and re-scans the invite. That is the accepted cost, and it is bounded to a population
the owner can enumerate by hand.

**Alternative considered — wait longer and fire on the same argument later.** Rejected: waiting
changes nothing that the argument depends on. The distribution facts are already final (a tag cannot
retroactively stop being an ancestor), so additional calendar time buys no additional evidence — only
more time spent shipping a reinstall that resurrects, sometimes into the wrong event.

**Alternative considered — ship a one-shot "seen a ≥13b build" marker and gate on it.** Rejected: it
is the install-scoped marker the staging requirement explicitly forbids, wearing a different hat. It
would have to be durable across a reinstall to be meaningful, which means the Keychain, which means
keeping the seat and the pin this change exists to retire.

### D2 — `isConfigFileAbsence` becomes solely load-bearing, and that is recorded rather than mitigated

This is the substantive consequence of the change, and until now it was **written down nowhere**.

Today the read path is a two-of-two vote on the leave decision. A `Missing` from the file only
becomes `ConfigRead.None` if the Keychain fallback *also* answers `Absent`. So a **wrong** `Missing`
— a read error misclassified into the not-found class — is silently caught on any migrated device:
the fallback finds the legacy item, the read answers `Joined`, and the membership survives. The
fallback has been a second opinion on `isConfigFileAbsence`'s verdict for as long as it has existed,
which is precisely why nobody had to think about that classifier's blast radius.

After this change the vote is one-of-one. `isConfigFileAbsence(domain, code)` — the `NSError`
domain/code table at
`adapter/ios/ext-safe/src/iosMain/kotlin/app/snapsync/config/ConfigFileAbsence.kt` (moved there from
`model/` in `ce1f75c3` by `enforce-port-boundary`, because its inputs are a platform encoding) — is
the **only** thing standing between a misclassified read failure and an unintended leave: marker
cleared, ledger clear-and-seeded, discovery cursor reset, and the screen back on the setup gate, with
no error anywhere and nothing to undo it. It is a five-line pure function, and it is now one of the
highest-consequence five lines in the app.

**The classifier is not changed.** It is already built for exactly this: a closed whitelist
(`NSFileReadNoSuchFileError`, `NSFileNoSuchFileError`, POSIX `ENOENT`) whose `else` arm answers
`false` rather than guessing, grounded on Apple's data-protection contract that a protected read
before first unlock fails permission-class and never not-found. There is no hardening available that
it does not already have; the honest response to "this is now load-bearing" is to **say so**, in the
spec and at the site, so the next person to widen that whitelist knows what they are widening.

Accordingly this change adds the statement to three places and the logic to none:

- the `event-rejoin-reconciliation` requirement (a sentence in the delta, per the change brief);
- the `ConfigFileAbsence.kt` KDoc — the file a widening edit is actually opened in;
- the `ConfigFileRead.Missing` KDoc in `ports/`, where the neutral fact is consumed.

**Alternative considered — add a corroborating check (e.g. `fileExistsAtPath` before believing a
`Missing`).** Rejected. It answers a *different* question than the read did, at a different instant,
with its own error surface; two disagreeing absence oracles is a worse position than one whose
whitelist is closed. It also costs a second blocking file call in a constructor that
`ConstructorBlockingTest` already grandfathers under protest.

**Alternative considered — treat `Missing` as unreadable and require a positive "left" marker.**
Rejected: it inverts the capability's decided semantics (an App-Group file dying with the install
*is* the leave signal) and leaves no road to "this device left the event" at all, since a left device
is precisely one with no file.

### D3 — The orphaned legacy Keychain item is left in place

Already-migrated devices keep a `app.snapsync.config`/`eventconfig` Keychain item that nothing will
ever read again. It survives app deletion (Keychain items do), so it persists indefinitely.

Left in place deliberately. Purging it would require keeping alive exactly the three things this
change removes — the seat, the runtime-identity pin, and a Keychain call on the leave path — in order
to delete data that no code path can observe. A dead item costs a few hundred bytes in a keychain the
user cannot see; the purge costs the whole retirement. The `event-link` delta records that the
orphan is knowingly abandoned, so a future reader finds an explanation rather than an oversight.

**Alternative considered — one-shot purge at first launch, then delete the seat in a Stage 3.**
Rejected: a third stage for byte hygiene, gated on a second soak, is more staging than the whole
migration warranted.

### D4 — `configReadFrom` is deleted rather than kept "for symmetry"

`ports/ConfigPorts.configReadFrom(read: KeychainRead, decode) : ConfigRead` maps a raw Keychain read
onto the three-state `ConfigRead`. Its only production caller is `KeychainConfigReader`. Keeping it
would leave a `ports/` function whose whole purpose is translating a storage backend the app no
longer has, next to the one that translates the backend it does — an invitation to reach for the
wrong one. `KeychainRead` itself stays: it is the `Keychain` port's own three-state read, used by
device identity, the attest store, and the album-map migration.

Its test file `ConfigReadTest` is deleted with it. Nothing is lost in coverage: every branch it pinned
— decodable → `Joined`, absent → `None`, unreadable → `Unavailable` and never `None`, undecodable →
`None` — has a live file-side counterpart in `ConfigFileReadTest`, except the Keychain-specific
"undecodable legacy item is `None`" rule, which is deleted because the rule itself is deleted (it
deliberately never transferred to the file: an undecodable *file* is `Unavailable`, per
`CONFIG_FILE_UNUSABLE_STATUS`).

### D5 — `configReadViaFile` keeps existing, with a three-arm body

After losing `fallback`, `migrate` and `repair` it is a bare `when` over `ConfigFileRead` — small
enough to ask whether it should be inlined into the adapter. It should not: `event-link` requires
that *"the envelope codec and the read algorithm SHALL be pure `:domain` functions covered in
`commonTest` (JVM **and** iOS simulator); the adapter SHALL contain only file IO and error mapping."*
Inlining moves the leave decision into `iosMain`, where — per the very rationale that moved
`isConfigFileAbsence` out of `model/` — it would only be testable on macOS. The one decision in the
app that can silently log a user out stays on the pure, dual-target-tested side of the seam.

### D6 — What still guards against the seat growing back, now that the pin is gone

Retiring the pin is not free of consequence, so state what remains and what does not.

- **An unscoped resurrection still fails the build.** `RuntimeIdentityTest`'s unscoped-seat inventory
  is an **exact set** assertion over every `IosKeychain(...)` construction site. Removing
  `(app.snapsync.config, eventconfig)` from the expected set means a new unscoped construction of
  that seat makes the found set differ, and the gate fails — with its existing message about a new
  unscoped seat.
- **A *scoped* resurrection would not.** Scoped sites are only checked for the device-id seat's
  presence, not pinned as a set. So a future `IosKeychain(service = "app.snapsync.config", account =
  "eventconfig", accessGroup = …)` would pass every gate. This is a **stated blind spot**, not an
  oversight; it is also a narrow one, since a scoped read cannot find the unscoped items pre-11a
  builds wrote, which is what any resurrection would be after.
- **`KeychainContainmentTest` is unaffected** and keeps holding: any `SecItem*` reference outside
  `:adapter:ios:ext-safe` still fails the build, wherever it is aimed.

**Alternative considered — add `KeychainConfigReader` to `DeletionLedgerTest`.** Rejected. That
ledger's own contract is for *"the kind of thing that grows back innocently"* — a convenience
interface, a second uploader. A legacy-storage fallback for a storage backend that no longer exists
does not grow back innocently; anyone writing one is deliberately re-opening the Keychain, and the
containment gate plus the unscoped inventory already meet them there. Adding a row would be
inventory for its own sake, which is what the ledger's rationale warns against.

### D7 — A deferred platform-identifier pin is discharged early, and that is recorded as such

Deleting `configReadFrom` removed `ports/ConfigPorts.kt`'s only `KeychainRead`-typed function, so
the `Keychain` token left that file's code and `PlatformIdentifierTest`'s **deferred** pin for it
went stale. Because that gate is exact in both directions, it **failed** on the first run of this
change — which is precisely the behaviour its own requirement specifies ("the gate fails until its
pin is removed, so the pin list cannot describe absent code"). The pin is deleted here.

Worth recording rather than doing silently, for two reasons. First, the requirement files that pin
under the `Keychain` port family's reshape as its expiry trigger, and that reshape has **not**
happened — so an expiry trigger is a floor, not a schedule, and a deferred pin may be discharged by
whatever removes the code. Second, it is a small piece of evidence for the exact-in-both-directions
design that the `enforce-port-boundary` change argued for: the receipt could not outlive the debt
even by accident.

**Ordering consequence.** That requirement lives in `enforce-port-boundary`'s delta, which is
implemented and committed on this branch but not yet synced into `openspec/specs/`. This change's
`MODIFIED` of it therefore stacks on that one, and syncing out of order fails loudly (openspec throws
"MODIFIED failed … not found") rather than silently — the failure mode `CLAUDE.md` warns about, where
two changes each carrying a `MODIFIED` copy of one requirement let the second silently revert the
first, does not apply here because the two touch different requirements in that file.

## Risks / Trade-offs

- **A dormant pre-11a device is logged out with no notice** → Accepted, and bounded by D1 to internal
  TestFlight installs from before 2026-07-18 that have not started a process since. The user-visible
  outcome is the setup gate and a re-scan; nothing is deleted from the backend, no photo is lost, and
  the re-join reconciliation means the re-scan re-uploads nothing already stored.
- **A misclassified `NSError` now logs a device out for real** → This is D2, and it is mitigated by
  documentation rather than code, deliberately. The classifier's whitelist is closed and its `else`
  arm answers `false`, so the failure requires Apple to report a *not-found* code for a
  non-absence — the one shape the data-protection contract rules out. The realistic path to this risk
  is a future edit widening the whitelist, which is exactly what the three added notes address.
- **Reinstall stops resurrecting — a real user could experience this as data loss** → Intended
  behaviour, decided in `changes/archive/2026-07-18-migrate-config-to-app-group-file` D5. It is also
  the *less* surprising of the two options: today a user who deletes the app to leave an event finds
  themselves silently back in it (and possibly in the wrong one, per the switch divergence), which is
  the behaviour that actually violates expectation.
- **iOS tests cannot run on Linux** → The changed code is `iosMain` (`FileBackedConfigStore`) plus
  pure `commonMain`/`commonTest`. The pure side runs on JVM in `./gradlew build`; the iOS side is
  compile-checked by `./gradlew compileIosMainKotlinMetadata`, and the simulator run is verified
  separately by the owner.

## Migration Plan

There is no data migration and no rollout sequencing — the deletion *is* the migration's final step,
and its precondition (D1) is already satisfied on every merged commit.

**Rollback**: revert the commit. Devices that read a missing file under the reverted build consult the
legacy item again and resurrect exactly as they do today; devices that already left under the new
build have an unchanged legacy item (this change stops *deleting* it, so nothing is destroyed that a
revert would need back). The one non-recoverable direction is a device that leaves under the new
build and is reverted: it resurrects the stale legacy membership — which is Stage-1 behaviour, not
new damage.

## Open Questions

None. The gate is discharged (D1), the classifier decision is settled (D2), and the orphan is
knowingly abandoned (D3).
