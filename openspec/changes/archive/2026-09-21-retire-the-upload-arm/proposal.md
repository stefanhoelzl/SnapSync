## Why

The upload arm is five concepts — a held mechanism instance (`UploadArm.current`), a kind→instance table
(`uploadMechanismTable`), a relinquish wrapper (`RelinquishThenRun`), an idle stand-in
(`IdleUploadMechanism`), and a two-verb `UploadProducer` seam — serving one requirement: exactly one upload
mechanism writes the ledger at a time. Only two of them carry facts: the resolution rule
(`resolveUploadMechanism`) and the membership transitions that reconcile the OS's extension registration and
the app engine's heartbeat. The rest is indirection. It also carries a shipped defect: the held instance starts
at `IdleUploadMechanism` and only a UI-launch transition moves it, so a **cold background wake** (the upload
heartbeat, a silent push) reaches the idle stand-in, does nothing, and the heartbeat chain silently ends. Photos
taken while the app is closed then wait for the user to open it.

## What Changes

- **Deleted:** `UploadArm` and its held `current`, `uploadMechanismTable` + `requireConsistent`,
  `RelinquishThenRun`, `IdleUploadMechanism`, the `UploadProducer` / `UploadMechanismRuntime` seam, and the
  OS-driven mechanism's four hand-written trigger declines.
- **Kept, unchanged:** `resolveUploadMechanism` (and its rig override), the OS-driven disable → demote →
  enable registration ritual, `UploadPushReceiver`'s `GRANTED`-exactly guard, and every ledger clear/load rule
  from `join-loads-leave-clears`.
- **Exclusivity moves from structural to gated — a stated weakening.** Today only one mechanism is ever held,
  so a second writer has no expression. After this change each engine **declines at its own entry gate** when it
  is not the one that may run. Both engines' cycles can now be constructed in one install; what prevents two
  ledger writers is the gate decision plus the registration state, not the absence of an object. (The
  structural claim was already porous: a background-`URLSession` relaunch constructs and runs the app engine's
  cycle under the OS-driven mechanism today — see design.)
- **Four membership transitions** — join, reconfigure, permission change, leave — plus an explicit **launch**
  reconcile, bound in one tested, stateless place in `feature/upload`. Each reconciles two things from the
  resolved kind and the membership's upload posture: whether the extension should be registered, and whether the
  app engine's heartbeat should be armed.
- **Launch compares; only a join forces the repair.** Launch no longer rides the permission `StateFlow`'s
  replay. It registers or deregisters only when what should be registered differs from what the OS reports
  (read beside the current permission, because the OS's read is grant-dependent). The forced disable → demote →
  enable runs at **join**; any transition that must register still registers through the ritual (a bare enable
  over a stale record fails with 3202). Accepted consequence: a stale record that still reads "enabled" is
  repaired only at the next join, and the extension's in-flight OS jobs survive app launches.
- **Photo permission joins the cycle's entry gate, in both processes.** Two new gate outcomes and two new cycle
  outcomes, both publishing **nothing** (never the empty manifest `Declined` publishes) and returning
  `SKIPPED`:
  - *not resolved here* (app process, when the resolved kind is not the app-driven one): touches nothing — no
    settle, no stranded pass, no ledger write;
  - *withheld for permission* (extension process, when the grant is not `GRANTED`): acknowledges the terminal
    jobs the OS presented (avoiding error 50008) but re-creates no retry and runs no stranded pass.
  The decision is made inside `cycleGate`, **before** the selection policy is built, so a `NOT_DETERMINED`
  grant never reaches the album reader that would raise iOS's permission dialog from a background wake.
- **App triggers go to the app engine unconditionally** (foreground, silent push, heartbeat, selection change);
  its gate decides. **Announced fix:** cold background wakes now run real cycles, so the heartbeat chain no
  longer dies on a cold launch and photos keep uploading in the background.
- **Permission revocation disarms the app engine** (cancels transfers and the heartbeat), as
  `ios-url-session-upload` already requires and the code currently does not.
- The rig's mechanism pin (app memory only; the extension cannot read it) **triggers the registration
  reconcile** when set.
- `ProducerExclusivityTest` is **re-pointed**, not retired: it drives the per-process admission and the
  transitions over a fake registration and a fake app engine, asserting no reachable state admits two writers.

## Capabilities

### New Capabilities

(none)

### Modified Capabilities

- `upload-lifecycle`: the arm/orchestrator/producer requirements become the four transitions plus launch; the
  exactly-one invariant becomes gated; the entry decision gains the two admission outcomes; settling and
  publication cover them; the idle-mechanism requirement is removed; triggers go to the app engine.
- `architecture-guards`: "The upload producers are never both started" is re-pointed at the gates and the
  registration state.
- `limited-photo-access`: the extension declines under a partial grant at its own gate; no deregistration is
  attempted under a partial grant (it is refused — 3311); the app runs scoped to the selection.
- `ios-photokit-upload`: registration happens at transitions (forced at join, compared elsewhere); the
  extension's permission read and its withheld outcome; `stop()` wording becomes deregistration.
- `ios-url-session-upload`: `start()`/`stop()` become the app engine's arm/disarm, fired by the transitions;
  mutual exclusion is gated; the restart signal fires at arm.
- `reconfigure-membership`: enabling upload reconciles the mechanisms (registering when needed) instead of
  "arming the producer".
- `ios-app-shell`: no transition rides the permission replay; host assembly calls an explicit launch
  reconcile; entry points delegate to the app engine; the OS-driven tier's "constructs no `LedgerWriter`"
  becomes "writes no ledger record".

## Impact

- **Code:** `:domain` `feature/upload` (delete the arm cluster; add the transitions, the admission input to
  `cycleGate`, two `CycleGate` + two `CycleOutcome` variants, the narrow settle), `compose/` (`AppPorts`,
  `UploadPorts`, `installPermissionSubscriptions`, the `Provision` / `LeaveEvent` / `ReconfigureEvent` /
  `MembershipEntry` bindings), `flow/Provision` (an effect lambda replaces the `UploadArm` parameter — no new
  branch), `:adapter:ios:ext-safe` (the `PHAuthorizationStatus` → `PermissionStatus` mapping moves here;
  `PhotoLibraryPermission` delegates), `:app:ios` / `:app:ios:extension` roots (wiring), `:test:rig` (the pin
  triggers the reconcile), `:test:world`, `:test:architecture` (`ProducerExclusivityTest`,
  `CompositionSeamTest` pins).
- **Budgets:** `AppPorts` falls from 45 to 44 fields (`osDrivenUpload` + `relinquishOsRegistration` → one
  registration field); `UploadPorts` gains one required field (18 → 19). `Provision` stays at 9 parameters and
  gains no branch. `./gradlew architectureDiagrams` must be re-run.
- **Data:** no schema change; the ledger is untouched. Rollback is a revert: the only durable residue is the
  OS's registration record, which a rolled-back build re-registers on its next UI launch.
- **Release:** customer-visible (background uploads resume after cold wakes) — changelog label `bug`.
- **Out of scope:** reducing the stranded repair to one process-start rule (phase 5). The restart signal keeps
  firing where the app engine is armed, including at launch.
