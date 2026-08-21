## ADDED Requirements

### Requirement: An identity may be supplied to a host whose secure store cannot serve one

The app SHALL resolve its device identity through a supplier that consults, in order, the secure store and
then a **read-only fallback source**. Production SHALL never write that fallback source; its presence is the
whole discriminator, and nothing in a shipped build creates it.

This exists because a host can be unable to serve an identity at all rather than merely lacking one. On the
iOS simulator, `keychain-access-groups` makes the app un-launchable in every signing form measured, and
omitting it yields `errSecMissingEntitlement` (-34018) — so the store answers neither "here it is" nor "there
is none".

The fallback SHALL sit **above** the secure-store adapter, at the supplier, and SHALL NOT be expressed as an
absence for that adapter to fill. `errSecMissingEntitlement` is a **read error**, not `errSecItemNotFound`,
and the adapter deliberately never mints on a read error — that distinction is the locked-device fix
(`SecureStoreUnavailable`), and re-classifying an unavailable store as an empty one would reintroduce the
failure it exists to prevent. The same applies to writes: the adapter's adopt and mint branches both write, so
on such a host every branch fails, not only the read.

Consequently:

- **Locked device**: the store reports unavailable, no fallback source is present, and the app defers exactly
  as it does today. This deferral is load-bearing and SHALL NOT regress.
- **Mis-signed build**: the store reports unavailable, no fallback source is present, and resolution fails
  loudly, as it does today.
- **A host given an identity**: the fallback source is present and its value is adopted verbatim.

The supplier SHALL fill an absence and SHALL NOT overwrite: where the secure store resolves an identity, that
identity SHALL win and the fallback SHALL be ignored, with the fact recorded. A written-once, unrecoverable
value SHALL never be replaced by a supplied one.

Every consumer SHALL take the identity as a **supplier** rather than as a resolved value, so that a failed
resolve is retried on the next access rather than fixed for the process. The resolution SHALL NOT memoize a
failure; this property SHALL be pinned by a test rather than inherited from the standard library's behavior.

#### Scenario: A supplied identity is adopted where the store cannot serve one
- **WHEN** the secure store reports unavailable and a fallback source is present
- **THEN** the supplied identity is adopted verbatim and used for enrollment, uploads, and the device
  partition

#### Scenario: A resolved identity is never overwritten
- **WHEN** the secure store resolves an identity and a fallback source is also present
- **THEN** the store's identity wins, the fallback is ignored, and the fact that it was ignored is recorded

#### Scenario: A locked device still defers
- **WHEN** the secure store reports unavailable before first unlock and no fallback source is present
- **THEN** the app defers as it does today, and no identity is minted

#### Scenario: A mis-signed build still fails loudly
- **WHEN** the secure store reports unavailable because of a signing fault and no fallback source is present
- **THEN** resolution fails loudly rather than silently minting a new identity

#### Scenario: A failed resolve is retried, not cached
- **WHEN** an identity resolution fails and a later access occurs after the condition has cleared
- **THEN** the later access resolves successfully, because the failure was not memoized

#### Scenario: Production never creates the fallback source
- **WHEN** a shipped build runs on any host
- **THEN** it never writes the fallback source, so its absence is the production case and its presence is
  always something outside the app put there
