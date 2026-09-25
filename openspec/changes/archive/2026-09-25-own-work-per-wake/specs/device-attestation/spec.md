## MODIFIED Requirements

### Requirement: The device token is minted by the app process and shared with the extension

The token SHALL be obtained by the **app** process and persisted in the shared Keychain access group, so
the upload extension reads the same value.

Each process MAY hold the last token it read **in memory** and serve requests from that copy, instead of
paying a Keychain read for every request it authenticates and every upload request it builds. The copy SHALL
never be stale against the process's **own** writes: every store, clear and compare-and-clear SHALL drop it
after the write, and a read that raced the write SHALL NOT re-install what it read. A read that fails (a
locked store, a missing entitlement) SHALL NOT be held. The other process's writes are invisible to a copy, so
each process SHALL re-read the store of record at these points, and the copy's staleness is bounded by them:

- **the app**, at every attestation decision — every wake the next requirement names. A token the extension
  cleared is therefore seen, and renewed, at the next wake;
- **the extension**, at the start of every OS invocation (`process()`). A renewal the app stored is therefore
  carried by the next invocation;
- **both**, on a credential rejection: the compare-and-clear SHALL compare against the store of record, never
  against the copy, and SHALL re-read after it; and
- **both**, when minting a retry's upload request (capability `edge-upload-provider`, "A retry's request
  carries the credential from its store of record").

Between those points, a copy may lag the other process's latest write. In particular, inside one extension
invocation a first request can carry a token the app has replaced since that invocation's first read. The
token it carries is still one the backend minted for this device. At worst it is answered `401`, which is
the retryable failure every upload path already handles, and the retry reads the store of record. Decision
record: `changes/own-work-per-wake` (D13).

The extension SHALL NOT attest and SHALL NOT renew: App Attest is **unavailable** in the extension
process (`DCAppAttestService.isSupported` reports `false` there, and `true` in the app). The extension
SHALL send whatever token it read from the Keychain (its in-memory copy, within the bounds above), SHALL NOT
block on a refresh, and SHALL send it as-is — including when it has expired.

The token's Keychain item SHALL use an accessibility class permitting reads while the device is **locked**
once it has been unlocked since boot (`kSecAttrAccessibleAfterFirstUnlock`), because the extension runs on
an idle — therefore usually locked — device. It SHALL NOT be restricted to the device, so that it is
restorable from an encrypted backup alongside the device id.

#### Scenario: The extension reads the app's token

- **WHEN** the extension builds an upload request
- **THEN** it sends the token the app persisted in the shared Keychain access group, as read at the start of
  the current invocation (or later, after a rejection)

#### Scenario: An app renewal reaches the next extension invocation

- **WHEN** the extension read token T1 during one invocation, and the app then renews and stores T2
- **THEN** the extension's next invocation re-reads the shared item, and its requests carry T2

#### Scenario: A token the extension cleared is renewed at the app's next wake

- **WHEN** the extension's request carrying T1 is rejected and it clears T1 from the shared item, while the
  app still holds T1 in memory
- **THEN** at the app's next wake the attestation decision re-reads the shared item, finds no token, and
  renews

#### Scenario: A process never reads its own write stale

- **WHEN** a process stores or clears the token
- **THEN** the next token read in that process answers the value it wrote, not the copy it held before

#### Scenario: The extension never attests

- **WHEN** the extension finds no token, or an expired one
- **THEN** it attests nothing and renews nothing; it proceeds with what it has (or none) and the request
  fails, to be retried

#### Scenario: A locked background upload reads the token

- **WHEN** the OS invokes the extension while the device is locked, and the device has been unlocked at
  least once since boot
- **THEN** the token is read successfully

