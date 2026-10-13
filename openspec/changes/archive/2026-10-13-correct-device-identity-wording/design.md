# Design

## Context

See proposal.md — Why. The identifier the app is known by is minted once per phone and kept in the platform's
protected store: on iPhone a random UUID in the shared Keychain item (which normally outlives deleting the app), on
Android a name-based UUID over the identifier Android scopes to SnapSync's signing key and the user (`ANDROID_ID`,
which survives a reinstall and resets with a factory reset). The Android lifetime was chosen so a reinstalled phone
stays the same device to its events — the same capacity slot, the same shared photos
(`changes/archive/2026-09-30-android-receiving`); Auto Backup is off so no restore can give two phones one identity.

Three statements say otherwise: the `privacy-security` spec ("a random identifier" per install, in three
requirements), the published Privacy Policy ("Each install generates a random identifier"), and the Play data-safety
notes ("the random install ID", treated as part of the photos). The operator has already declared the identifier in
the Play Console as "Device or other IDs" (App functionality, not shared).

## Goals / Non-Goals

**Goals:**
- Every statement a user or a store reads about the identifier is true on both platforms.

**Non-Goals:**
- Changing how the identifier is minted or how long it lives. A random per-install id on Android would turn every
  reinstall into a new device — lost capacity slot, orphaned photos — to make a sentence true.
- The failure-reporting SDK's own random per-install identifier (requirement "Automatic failure reports are minimal
  and anonymous") — that one IS random per install, and its wording stays. The same requirement's "random identifiers
  … of the device, the event and its photos" does not hold (the device's, above; a photo's is the platform's own) and
  loses "random".

## Decisions

- **Correct the words, keep the behaviour.** The behaviour is deliberate and recorded; the wording was inherited from
  the iPhone-only era. Alternative — make the code match the old words (always random, per install): rejected above.
- **The spec states the lifetime as a MAY ("may stay the same after a reinstall"), not a promise.** Whether the
  iPhone's Keychain item survives deleting the app is the platform's behaviour, not SnapSync's guarantee; the spec
  discloses it, as a privacy statement must, without making it a contract the app could be held to on a future iOS.
  The android-receiving record took the same line for the receive-again gap.
- **"Linked to no other app".** On iPhone the value is random; on Android, Android hands each signing key its own
  value, and the service receives a digest of it, never the value itself — so no other app can match it.
- **The Privacy Policy says it per platform**, as the requirement already demands for the integrity check and the
  push token. One paragraph, two sentences of difference.

## Risks / Trade-offs

- [A reader takes "may stay the same after a reinstall" as a tracking concern] → the policy says what it is for
  (telling devices apart within an event), that it is linked to no person, and how to end it (a factory reset).
- [The store review reads the identifier as more than app functionality] → it is declared as "Device or other IDs",
  App functionality, not shared — the declaration matches the policy.
