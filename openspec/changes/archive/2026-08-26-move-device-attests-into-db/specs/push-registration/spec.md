## MODIFIED Requirements

### Requirement: Registration timing — launch, join, and rotation

The app SHALL register the token when it becomes available after launch, **on join** (when the device
provisions an event), and again whenever the APNs token rotates (a new token delivered by the OS).
Registration SHALL be idempotent — re-registering the same token overwrites the device's stored
registration (last-write-wins at the endpoint) — so repeated launches and joins with an unchanged token
are harmless.

The registration write SHALL be treated as **refusable**. It requires an attestation record on the
backend, and answers `401` when there is none (capability `api-endpoints`); the app already recovers from
any `401` by obtaining a fresh credential, and obtaining one SHALL re-send the registration. Without that
retry the device would go unregistered until its next launch, because it writes its registration only once
per token the OS delivers.

Registering on join exists to close a **warm-rejoin** window: a device can hold a backend record whose
push registration is absent, and would then receive no silent pushes until its next launch. Two things
produce that state — a device that re-attested after its record was collected (attestation records no push
token, so the recreated row's registration columns are empty), and one whose registration write was
refused. Registering on join restores it immediately.

The window is **narrower than it was**, and the reason is worth keeping: the scheduled cleanup no longer
collects a device's record while a token minted for it can still verify (capability
`scheduled-cleanup`). A device whose record is gone therefore cannot rejoin warm — it holds no usable
credential, so it must attest first, and attesting recreates the record. What remains is the absent
*registration*, not an absent record.

Registration SHALL NOT be tied to every foreground (too frequent); launch, join, and rotation are the
triggers.

#### Scenario: Registration fires on launch once the token is available

- **WHEN** the app launches and the OS delivers the APNs token
- **THEN** `PushRegistration` runs for that token

#### Scenario: Registration fires on join

- **WHEN** the device provisions (joins) an event and an APNs token is available
- **THEN** `PushRegistration` runs for that token, re-registering it with the backend

#### Scenario: Rotation re-registers

- **WHEN** the OS delivers a new (rotated) APNs token
- **THEN** `PushRegistration` runs again with the new token, replacing the stored registration

#### Scenario: A refused registration is re-sent once a fresh credential is obtained

- **WHEN** the registration write is refused because the backend holds no attestation for the device
- **THEN** the app attests afresh and re-sends the registration, without waiting for the OS to deliver
  another APNs token
