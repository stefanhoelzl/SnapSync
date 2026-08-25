## ADDED Requirements

### Requirement: A key's readers follow it to the rendering that owns it

Every reader of a resolved value SHALL read it from the rendering the key inventory assigns to that key.
No reader SHALL read a fragment-owned key from an authored file the key has left, and a reader that
resolves an empty value for a key it requires SHALL fail closed — refusing to produce its output and
naming the key and the file — rather than substituting the empty value.

This is distinct from the requirement that a consumer without a rendering fails loudly. That one governs
a rendering which is **absent**. This one governs a reader still pointed at a file which is **present**
and simply no longer carries the key: the read succeeds, yields nothing, and nothing distinguishes
"this file does not assign that key" from "that key's value is empty". Where the value composes an
identity — a signing prefix, a bundle id, a domain — an empty substitution produces a well-formed
artifact making a false claim, which no validity check downstream can detect.

#### Scenario: A reader left behind on a moved key fails closed

- **WHEN** a reader extracts a fragment-owned key from an authored file that no longer assigns it
- **THEN** it fails naming the key and the file, rather than proceeding with an empty value

#### Scenario: An empty required value is never substituted

- **WHEN** a reader resolves an empty value for a key it requires
- **THEN** it produces no output, rather than emitting an artifact with the empty value interpolated

#### Scenario: A reader of the owning rendering succeeds

- **WHEN** a reader reads a key from the rendering the inventory assigns it, and the resolver has run
- **THEN** it resolves the key's value and proceeds
