# Design — migrate-config-to-app-group-file

## Context

Migration step 11a (PLAN, rebuilt after review — the original design lost joined devices). The
persisted `EventConfig` moves from a shared-Keychain item to an App-Group file, because the decided
end state is **reinstall = left the event** and only install-scoped storage can express it. The
step is a one-way door on a TestFlight channel with no realistic revert other than fix-forward, so
the design is dominated by two windows: the **false-leave window** (a joined device whose first
post-update read finds no file) and the **revert window** (a previous build that reads only the
Keychain). Session A's settled inputs ⑤ (backup2: App-Group files are backup-extractable) and ⑥
(the CUFUA read-before-first-unlock error shape) are inputs to this design, not afterthoughts.

## Goals / Non-Goals

**Goals**: the file is the storage of record for both processes; zero joined devices read a false
leave at any point of the rollout; a revert build finds a live config for the whole soak window; a
future format change can never read as a leave on this build; every decision branch runs on JVM +
iOS simulator.

**Non-Goals**: deleting the Keychain entry (13b+, explicitly deferred — and with it the actual
"reinstall = left" flip); changing any port interface; changing the world harness (its three-state
`ConfigReader` lever is port-shaped and unaffected); multi-event config.

## Decisions

### D1 — The migration lives inside the adapter, not app startup

`FileBackedConfigStore.read()`: file → on definitively-missing, Keychain → on found, atomically
write the file and return; on found-nothing, `None`. The OS can invoke the upload extension before
the user ever foregrounds the updated app, and the extension's leave side acts on `None` by
clearing the `joinedEventId` marker — so a startup-hook migration is a false leave on every joined
device whose extension wakes first. App and appex update atomically, so both processes carry the
adapter and **whichever reads first migrates**. The migration write is best-effort: a failed write
returns the Keychain's answer anyway and retries on the next read.

**Compare-and-repair** (review hardening A1): after the migrate write, the pure algorithm re-reads
the Keychain; if it no longer holds the value just migrated — a concurrent save/clear in the other
process landed between the read and the write, so the file now holds a stale clobber — the adapter
repairs the file to the fresh state (overwrite on `Joined`, delete otherwise) and the **fresh**
state is returned. This shrinks the stale-migrate window from the whole read-to-write span to the
instruction width between the re-read and the return; the residual race is pinned by a
`commonTest` and bounded by the same next-read retry as everything else here.

### D2 — Copy, don't move; and the write orderings

The Keychain item keeps being written through on **every** save and clear until a later change
(13b+) deletes it: a revert build still finds a live config. Orderings are deliberate and
asymmetric:

- **save = file first, Keychain second.** The file is what this build reads; a crash between the
  two leaves the file authoritative and the Keychain copy one save stale — repaired by the next
  save **of any config, an equal one included**: the adapter deliberately carries no equal-config
  early return above the write-through (review hardening A2 — an outer guard seeded from the file
  would skip exactly the re-save that repairs a torn save's stale Keychain copy; the inner
  Keychain save has its own idempotence, and the `StateFlow` conflates equal values, so the
  no-redundant-emission contract holds). The stale copy is never consulted by this build (the
  fallback runs only on a *missing* file) and is exactly the revert build's exposure, identical
  to the pre-existing torn-save exposure.
- **clear = Keychain first, file second.** The reverse order would create precisely the state D1's
  fallback resurrects (file missing + Keychain present): a crash between the deletes would
  silently undo the user's leave. Keychain-first, a crash leaves the file present — THIS build
  stays joined and the leave simply retries, while a **revert build** (which reads only the
  Keychain) already reads left. That divergence is the accepted cost: the alternative — a
  resurrected leave — would never surface, whereas a joined-and-retrying device is visible and
  self-heals on the retry. Both halves are idempotent.

  The old spec requirement's **legacy-item accessibility upgrade** (a weaker-class Keychain item
  migrated in place on first successful read) is no longer spec text, but it is still performed —
  `KeychainConfigStore` is unchanged inside the composition and keeps upgrading on every
  fallback/write-through read — and becomes moot when 13b deletes the item.

### D3 — Absent is the not-found error class only (settle-list ⑥)

Settled from the API contract (Apple's data-protection documentation): a protected-file read
before first unlock fails **permission-class** (`NSFileReadNoPermissionError` 257 / POSIX
`EPERM`), never not-found. Therefore: absent = `NSCocoaErrorDomain` 260 (`NSFileReadNoSuchFileError`)
or 4 (`NSFileNoSuchFileError`, the delete path's shape) or `NSPOSIXErrorDomain` 2 (`ENOENT`) —
and **any other error whatsoever** (unknown domain, unknown code, nil-error read failure, missing
App-Group container) is unreadable. The classifier is deliberately a whitelist of absence, so an
unexpected error shape fails safe (defer, retry next cycle) rather than leaving. **Verification
trigger**: Session C observes the real pre-first-unlock error on device; if the shape differs from
the contract, only the safe side can have absorbed it.

### D4 — Versioned envelope; foreign content is unreadable, never absent

The file holds `{"v":1,"payload":<EventConfig>}` (`model/ConfigFile.kt`, pure, `commonTest`).
`v==1` + payload decodes → joined; `v==1` + payload unusable (e.g. no `minPhotoDate`) →
**unreadable** (`ConfigRead.Unavailable` with the `CONFIG_FILE_UNUSABLE_STATUS` sentinel, `-2`) —
REVISED by the law review from the first draft's None: the Keychain legacy item's undecodable→None
rule was a known, deliberate re-join path for items written before the cutoff existed, but an
unusable **file** is a state this adapter's own atomic writes should make unreachable, i.e.
unexplained — and an unexplained state must defer, never clear the join marker. The user can still
re-scan (the setup gate shows either way and a save overwrites the file); the Keychain-side rule
stays in force untouched. Any other `v`, or text that is not an envelope → **unreadable**
(`ConfigRead.Unavailable` with the `CONFIG_FILE_FOREIGN_STATUS` sentinel, `-1`, distinguishable in
logs from both the unusable sentinel and a platform error code). The one-way-door scenario this buys: a future build writes v2; the
user reverts (or the OS runs a stale extension binary beside a newer app — impossible today since
app+appex update atomically, but free to defend); the old build defers instead of reading a leave.
Unknown *keys* are ignored on both envelope and payload, so additive same-version evolution needs
no bump.

### D5 — Reinstall = left is STAGED truth, and lands only at 13b+

The distinguisher the end state needs — "file absent because reinstall" vs "file absent because
update-in-place" — **cannot exist while the Keychain fallback does**: both states are (file
missing, Keychain present), and the fallback must resurrect the second or the rollout itself
false-leaves every joined device. PLAN says exactly this: the adapter-resident fallback closes the
update window, and the Keychain deletion is "a separate later change (13b or after)". So 11a's
honest contract, recorded in the `event-rejoin-reconciliation` delta: **while the write-through
lasts, a reinstall migrates like an update** (today's behavior, unchanged); when the Keychain copy
dies, (file missing + no Keychain copy to consult) becomes definitively-not-joined and reinstall =
left becomes true. No stronger mechanism (e.g. an install-marker file distinguishing reinstalls)
was added: it would flip the semantics earlier but *break the revert guarantee* — a reverted build
still resurrects from the Keychain regardless, so the early flip would only create app-vs-revert
divergence, not user-visible truth.

### D6 — Backup posture (settle-list ⑤): the file rides device backups, accepted

The config file is not excluded from backups and carries content under CUFUA protection, so it is
extractable from a device backup (⑤'s finding) and restores to a new device. Accepted, with the
posture change named: the Keychain item was deliberately **not** `…ThisDeviceOnly` — "the item
must ride an encrypted backup" (IosKeychain) — so backup/restore continuity of the membership is
the *existing, intended* behavior; the delta is that a **local unencrypted** backup now also
carries the eventId (a Keychain item rides only encrypted backups). Why not
`isExcludedFromBackup`: (a) it is unenforceable during the write-through window — a restore
carries the Keychain copy, and the D1 fallback would resurrect the file from it anyway; (b)
post-13b it would silently turn every backup-restore into a leave, a worse surprise than the one
it prevents; (c) the exposure is not new in kind — the eventId already rides backups verbatim in
`Documents/debug.log` (the un-redacted diagnostic channel logs `reconcile(eventId=…)` lines), and
possession of a backup implies possession of the paired device. The eventId is a capability;
anyone weighing this later starts from this paragraph.

### D7 — Protection class: CUFUA, matching every sibling store

`NSFileProtectionCompleteUntilFirstUserAuthentication`, applied atomically at write via
`NSDataWritingFileProtectionCompleteUntilFirstUserAuthentication` — the class the ledger and
download DBs already use, and the strongest one compatible with the OS-scheduled extension cycle
(the OS invokes it while the device is idle, usually locked; `NSFileProtectionComplete` would make
the membership unreadable exactly then, and the `:test:architecture` entitlements guard already
pins that neither process raises default protection to Complete).

### D8 — Pure decision layer; the adapter is IO only

`model/ConfigFile.kt`: envelope codec + `isConfigFileAbsence(domain, code)` (model may not name
ports, so the codec returns its own `ConfigFileDecode`). `ports/configReadViaFile(file, fallback,
migrate)`: the complete read algorithm — decode mapping, fallback-only-on-missing,
migrate-only-on-Joined, failed-migrate-does-not-fail-the-read — beside `configReadFrom`, so every
branch runs in `commonTest` on JVM **and** simulator. The adapter contributes ~60 lines of
`NSData`/`NSFileManager` calls and the `NSError` → `ConfigFileRead` mapping. What only a device
can exercise is enumerated for Session C in the PLAN row (the real CUFUA error shape, the atomic
rename on APFS, cross-process migration timing, the write-through against a real revert IPA).
