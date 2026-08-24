## MODIFIED Requirements

### Requirement: A build-time-only module is contained by compilation, not by a runtime check
A test-only module that links into a shipped-format binary SHALL be contained at compile time: it is linked only under an explicit build property, and a build without that property SHALL contain **no source of that module at all** — not a stub, not a no-op implementation, and not an inert runtime branch. Such a module SHALL still earn its modulehood the ordinary way, by withholding a third-party or platform dependency from every other module by compile error.

Where such a module needs a call site inside a shell, it SHALL contribute that source itself — a source
directory the shell's build script adds only under the property — rather than requiring the shell to carry
a permanently-compiled seam. The shell's own production source SHALL gain no declaration naming the
module, and any visibility widening it requires SHALL be the narrowest that compiles (`internal` before
`public`, so no platform framework header changes).

Where the thing to be contained is reached **through** a shell's own switch rather than by contributing a
call site — so that removing it would leave the shell naming a type that no longer exists — containment
SHALL be achieved by giving it its **own binary target** over its own module, rather than by keeping an
inert branch. A separate target linking neither the shell module nor the live graph makes inertness a
property the binary cannot express, rather than one that a set of no-op members must each preserve
correctly.

This is the inverse of `:adapter:generic:fake`, which never links into a shipped framework at all.

A dev/test control surface SHALL NOT rely on **runtime** inertness in a shipped binary. A launch-environment
variable is inert only because a production launch supplies no environment — a property of how the app is
started, not of what it contains — so it is not a containment mechanism. Where such a surface is wanted, it
belongs behind compile-time containment; a build-property-gated tree MAY read an environment variable,
because the file reading it is absent from a production build.

#### Scenario: A production build contains none of the module
- **WHEN** the app is built without the containment property (any CI, TestFlight, or App Store build)
- **THEN** neither the module nor any source it contributes is on the compile path, and the shipped binary
  contains no declaration of it

#### Scenario: The property links the module and its contributed call site together
- **WHEN** the app is built with the containment property set
- **THEN** the module is on the compile path **and** the source directory it contributes is added to the
  shell's source set, so the call site and the module it names arrive together and cannot be half-present

#### Scenario: A runtime-flag containment is rejected
- **WHEN** containment is proposed as a runtime check — a flag, an environment variable, or a no-op
  implementation compiled into every build
- **THEN** it SHALL be rejected for a module of this kind, because a shipped binary would then contain the
  code whose absence is the guarantee

#### Scenario: A surface reached through the shell's own switch gets its own target
- **WHEN** a dev/test composition is selected by a branch in the shell's own mode switch, so that gating its
  source alone would leave the shell naming a missing type
- **THEN** it is given its own binary target over its own module, linking neither the shell module nor the
  live graph, rather than remaining an inert branch in the shipped one

#### Scenario: The module still withholds a dependency
- **WHEN** the module is added to the module set
- **THEN** its withholding argument is recorded here, and the dependency it withholds is unreachable from
  every other module by compile error
