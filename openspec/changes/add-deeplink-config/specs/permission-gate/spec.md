## REMOVED Requirements

### Requirement: Gate replaces the status hero

**Reason**: The gate is no longer a single permission-first hero. It is generalized into the
two-input setup gate (config × permission) rendered as a stack of cards, owned by the new
`setup-gate` capability.
**Migration**: See `setup-gate` → "Two-input setup precedence" and "Setup gate is a stack of two
checkable cards". The permission step becomes one card; its `NOT_DETERMINED`/`DENIED` copy and CTAs
are preserved there.

### Requirement: Gate intents route through the container

**Reason**: Gate intent routing now spans permission and config (and gains `onOpenUrl`), so it is
owned by `setup-gate` rather than this permission-only capability.
**Migration**: See `setup-gate` → "Gate intents route through the container". `onRequestPermission`
and `onOpenSettings` keep their pass-through semantics; `onOpenUrl` is added for config deeplinks.

## MODIFIED Requirements

### Requirement: Permission domain contracts

The permission domain (`:domain:permission`) SHALL define `PermissionStatus` with exactly three
values — `NOT_DETERMINED`, `DENIED`, `GRANTED` — and two ports:

- `PermissionStatusSource` (state port): exposes `permission: StateFlow<PermissionStatus>`, a
  level-triggered state holder whose current value is always available synchronously. Every emission
  is the whole truth; consumers depend only on the latest value.
- `PermissionRequester` (command port): `fun request()` and `fun openSettings()`. Both are
  fire-and-forget — they MUST NOT return values and MUST NOT suspend. Status changes resulting from a
  command arrive exclusively via `PermissionStatusSource`.

Implementations MAY be a single object implementing both ports, but consumers SHALL depend on each
port separately. These contracts are consumed by the `setup-gate` capability, which renders the
permission step of the setup gate and routes its intents.

#### Scenario: Truth arrives only via the state port
- **WHEN** `request()` is invoked and the platform resolves the request
- **THEN** the new status is observed as an emission of `PermissionStatusSource.permission`, and
  `request()` itself communicates nothing

#### Scenario: Duplicate requests are harmless
- **WHEN** `request()` is invoked twice before the first resolves
- **THEN** no error occurs and the source ends up holding the single resolved status
