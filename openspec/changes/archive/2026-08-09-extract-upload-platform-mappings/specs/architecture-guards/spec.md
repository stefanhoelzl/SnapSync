## ADDED Requirements

### Requirement: The platform-vocabulary pin

For every Apple enumeration an adapter decodes with a **fallback arm**, `:test:architecture` SHALL
pin the complete set of constants that enumeration declares, with their exact values, and SHALL fail
the build on any delta — a constant added, removed, renamed, or re-valued.

The source of truth SHALL be the **Kotlin/Native platform klib** the build resolves, not a vendor
header, a documentation page, or a device observation. That klib is the compiler's own input, so it
states exactly what our source sees; reading it needs no Mac and no Xcode, and the pin therefore runs
on Linux inside `./gradlew build` rather than on macOS CI. Because the platform klibs ship prebuilt
inside the Kotlin/Native distribution, the declared set changes when the **Kotlin/Native version**
changes — so the pin fails on the version-bump pull request that introduces the new vocabulary, which
is the earliest moment the change is visible to anyone.

This is the inward mirror of "Runtime identity is pinned": that requirement pins literals **we** hold
which the OS also holds, so we cannot strand devices in the field; this one pins literals **Apple**
holds which we encode, so Apple cannot widen a vocabulary we decode without saying so. It is also the
first guard whose input is the toolchain's platform metadata rather than this repository's own source,
and it is aimed squarely at the blindness "The platform-identifier gate" already declares: that a
lexical scan cannot see a decoder over another system's values, and SHALL NOT be assumed to catch one.

A fallback arm is unavoidable in the decoders themselves — cinterop renders `NS_ENUM` as a type alias
over `NSInteger` plus loose constants, never a Kotlin `enum class`, so a `when` over one can never be
compiler-exhaustive. The pin is what supplies the exhaustiveness the language cannot.

The pinned inventory (this list is the contract of record; adding, removing, or re-valuing an entry
is a spec change to this requirement, deliberately):

- **`PHAssetResourceUploadJobState`** — `Registered` = 1, `Pending` = 2, `Failed` = 3,
  `Succeeded` = 4, `Cancelled` = 5. Decoded by the PhotoKit upload adapter's job-state table
  (capability `ios-photokit-upload`). An untaught state reaching the terminal-job drain is adjudicated
  as a retry-spent failure, which is safe but wrong.
- **`PHAssetResourceType`** — decoded by `photoKitResourceRole` (capability `gallery-status`), whose
  fallback **drops** the resource. An untaught original resource type is therefore a photo that never
  uploads, with no error anywhere — the silent-failure class this project treats as the worst outcome.

**What it does not cover, stated so a green run is not over-read:** the pin describes what the SDK
*declares*, not what the OS *returns*. A device may hand back a value no header carries, and the klib
reflects the SDK the Kotlin/Native distribution was built against rather than the iOS version on the
device. A green pin is therefore not a promise that a decoder's fallback arm is unreachable, and the
fallback arms SHALL remain load-bearing and SHALL keep handling an unrecognised value safely. Only a
device measurement settles what the runtime actually produces.

#### Scenario: A toolchain bump widens a pinned enumeration

- **WHEN** a Kotlin/Native version bump ships a platform klib in which a pinned enumeration declares a
  constant the inventory does not carry
- **THEN** `./gradlew build` fails on that pull request, naming the enumeration and the new constant
  with its value, so the decoder is taught before the bump merges

#### Scenario: A pinned constant changes value or disappears

- **WHEN** a pinned constant is removed, renamed, or bound to a different value in the resolved
  platform klib
- **THEN** the build fails naming the affected entry, rather than leaving a decoder silently mapping a
  value that no longer means what it meant

#### Scenario: The pin runs without a Mac

- **WHEN** the guard executes on Linux, where no Xcode and no Apple SDK is present
- **THEN** it resolves the platform klib from the Kotlin/Native distribution the build already
  provisions and completes normally, so the pin gates the required build rather than macOS CI alone

#### Scenario: An undeclared runtime value is out of scope

- **WHEN** a device returns a value for a pinned enumeration that appears in no SDK declaration
- **THEN** the pin is silent by construction, and the decoder's fallback arm handles the value safely —
  the guard's green result is never read as evidence that such a value cannot occur
