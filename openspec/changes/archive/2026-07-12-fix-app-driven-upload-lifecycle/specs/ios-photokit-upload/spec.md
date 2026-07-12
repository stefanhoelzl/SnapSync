## MODIFIED Requirements

### Requirement: Re-provision resets sync state

On a **valid `snapsync://` config (re)scan**, the host app SHALL re-provision the (possibly new) event
by persisting the config and driving the upload arm through the tier-neutral lifecycle
(`upload-lifecycle`). The mechanism below is **this tier's** (iOS ≥26.1) and SHALL NOT be applied on
the app-driven tier, which has no OS registration record to re-create (see `ios-url-session-upload`,
"App-driven lifecycle").

On this tier the re-provision's `start()` SHALL re-register the extension (the disable→enable toggle).
On its next cycle the extension reconciles against the per-device file listing (capability
`bunny-list-endpoint`, see `event-rejoin-reconciliation`): it **`resetTo`s** (atomic clear-and-seed)
the ledger to one already-uploaded row per stored file and **clears the discovery cursor** (forcing a
full re-enumeration). The device-global listing re-seeds the same files as already-uploaded, so
**nothing already stored re-uploads**, while the clear drops stale/phantom rows and the cursor clear
re-enumerates to find genuinely-unstored work. The device-global accumulator is **kept** and the
extension **re-projects** it to the **new** event's `device.json` path, then sets the joined-event
marker. The app decodes the deeplink only to gate this on a valid payload; the authoritative
decode/validate/persist still happens in the shared container intent.

The re-provision itself SHALL NOT clear the ledger or the discovery cursor
(`upload-lifecycle`): only the reconciliation's `resetTo` re-baselines them, from the authoritative
per-device listing.

#### Scenario: Valid re-scan reconciles and re-projects to the new event
- **WHEN** a valid `snapsync://` config URL is opened for a different event on iOS ≥26.1
- **THEN** the extension is re-registered (disable→enable), and the next cycle `resetTo`s the ledger
  from the per-device file listing, clears the discovery cursor, keeps the accumulator, and
  re-projects `device.json` to the new event path with the joined-event marker set

#### Scenario: Already-stored photos do not re-upload on a switch
- **WHEN** the device switches to an event whose photos are already present in its device
  byte-partition (capability `bunny-upload-endpoint`)
- **THEN** the clear-and-seed reconcile re-seeds them as already-uploaded and the extension creates no
  new upload jobs for them

#### Scenario: Invalid deeplink does not re-provision
- **WHEN** an opened URL fails config decoding
- **THEN** no re-provision occurs (the ledger, cursor, accumulator, and joined-event marker are untouched)

#### Scenario: The disable→enable toggle is confined to this tier
- **WHEN** the app re-provisions an event on iOS 18–26.0
- **THEN** `setUploadJobExtensionEnabled` is not called, and the app-driven producer's `start()` runs instead
