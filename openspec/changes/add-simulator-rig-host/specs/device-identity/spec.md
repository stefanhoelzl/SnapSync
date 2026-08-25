## REMOVED Requirements

### Requirement: An identity may be supplied to a host whose secure store cannot serve one

**Reason**: It solves a real problem — a host whose secure store answers neither "here it is" nor "there
is none" — with a **runtime discriminator in shipped code**. This change solves the same problem by
**compilation**, which is strictly stronger, and keeping both would give the simulator two identity
mechanisms.

The two designs agree entirely on the diagnosis, and that agreement is worth preserving: on the simulator
`keychain-access-groups` makes the app un-launchable in every signing form measured, omitting it yields
`errSecMissingEntitlement` (-34018), and that is a **read error** rather than `errSecItemNotFound` — so the
adapter's adopt and mint branches both fail, not only its read, and re-classifying an unavailable store as
an empty one would reintroduce the locked-device fault. Nothing here disputes any of that.

What is replaced is the remedy. A read-only fallback whose *presence* is the discriminator leaves a path in
every shipped binary that no shipped binary can reach, and its safety rests on a fact about the world —
that nothing ever creates that file. That fact is not free: an application **update preserves** the
App-Group container (measured on device, iOS 26.6, 2026-08-25), so a file planted by a rig build is
readable by a non-rig build installed over it. The fallback fires on unavailability, and on a device
unavailability means *locked* — so a stale plant would be adopted on a cold background wake, handing a real
device a test identity and orphaning its byte partition and its ledger, silently. Closing that needs a
staleness rule keyed to the planting process, and the ideal token for one — a process start instant — is
not reachable from a declared API (`klib dump-metadata`, Kotlin 2.4.0, `ios_arm64`: `platform.posix`
declares no `sysctl`, `proc_pidinfo` or `kinfo_proc`), so the rule can only bound the residual hole rather
than close it.

Binding the store per **compilation target** removes the discriminator instead of guarding it. `iosArm64`
— every shipped binary — keeps the addressed Keychain, unchanged. `iosSimulatorArm64`, whose output only
ever runs on a simulator, binds an App-Group file store. A device binary contains **no route** to the other
(measured on the klibs: zero occurrences), so there is no branch to take wrongly while locked, no plant to
go stale, and no rule to keep. Decision record: `changes/add-simulator-rig-host` D6.

Three properties this requirement asserted are **preserved, not dropped**, and are now properties of the
unmodified `Stable per-install device id` requirement rather than additions to it:

- a locked device still defers, because the store still reports unavailable and nothing mints;
- a mis-signed build still fails loudly, for the same reason;
- a failed resolve is still retried rather than memoized — `DeviceIdentityRetryTest` still pins it, its
  rationale rewritten from "an identity supplied after launch" to the locked-device case that motivated
  the property before a supplier existed.

The fourth — "a resolved identity is never overwritten" — becomes vacuous rather than weakened: with no
second source there is nothing to overwrite it with.

**Migration**: None. No durable state, no wire format, no stored value. The `iosArm64` target's compiled
output is unchanged, so no device's behaviour moves. Callers revert from the `resolveDeviceIdentity(role)`
supplier to `KeychainDeviceIdentity(role).deviceId()`, which now resolves its store from the compilation
target. The control channel's `POST /device/identity` verb and its writer are deleted with it; a simulator
needs no plant, because it mints and persists its own identity in the App-Group container on first launch.
