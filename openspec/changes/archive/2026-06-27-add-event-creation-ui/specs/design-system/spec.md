## ADDED Requirements

### Requirement: AppTextField semantic component

The design system SHALL provide an `AppTextField` semantic component — the app's first text input —
that the create-event screen composes from. `AppTextField(value, onValueChange, placeholder, enabled,
maxLength)` SHALL expose data-and-meaning parameters only: the current string value, a change
callback, a placeholder string, an enabled flag, and a maximum character count. It MUST NOT expose
appearance parameters (colors, text styles, shapes, elevations) or a `Modifier` parameter, and no
Material 3 type may appear in its signature; the Material 3 text-field containment lives inside the
component. The component SHALL enforce `maxLength` by refusing input beyond it.

#### Scenario: AppTextField signature is appearance-free
- **WHEN** the public signature of `AppTextField` is inspected
- **THEN** it carries only the value, change callback, placeholder, enabled flag, and max length — no
  colors, text styles, shapes, elevations, or `Modifier`, and no Material 3 type

#### Scenario: Max length is enforced by the component
- **WHEN** the field already holds `maxLength` characters and more input arrives
- **THEN** the value does not grow beyond `maxLength`

#### Scenario: Disabled field rejects input
- **WHEN** `AppTextField` is rendered with `enabled = false`
- **THEN** it does not invoke `onValueChange`

## REMOVED Requirements

### Requirement: SetupCard semantic container

**Reason**: `SetupCard`'s only consumer was the setup gate, which is retired by this change. The
create-event screen composes from `AppTextField` and `PrimaryButton`, and the permission step renders
as a `StatusHero`, so `SetupCard` has no remaining consumer.
**Migration**: None — `SetupCard` had no other call sites. If a future card-shaped step is needed, the
inventory grows demand-driven per the existing convention.
