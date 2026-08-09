## ADDED Requirements

### Requirement: A build-time-only module is contained by compilation, not by a runtime check
A test-only module that links into a shipped-format binary SHALL be contained at compile time: it is linked only under an explicit build property, and a build without that property SHALL contain **no source of that module at all** — not a stub, not a no-op implementation, and not an inert runtime branch. Such a module SHALL still earn its modulehood the ordinary way, by withholding a third-party or platform dependency from every other module by compile error.

Where such a module needs a call site inside a shell, it SHALL contribute that source itself — a source
directory the shell's build script adds only under the property — rather than requiring the shell to carry
a permanently-compiled seam. The shell's own production source SHALL gain no declaration naming the
module, and any visibility widening it requires SHALL be the narrowest that compiles (`internal` before
`public`, so no platform framework header changes).

This is the inverse of `:adapter:generic:fake`, which never links into a shipped framework at all. It is
distinguished from a dev/test **launch trigger** (a `SNAPSYNC_*` environment variable), which ships in
every binary and is inert only at runtime: a launch trigger's inertness is a testable runtime contract and
belongs to the shell's capability, whereas compile-time containment is a property of the module graph and
is proven by the absence of the code.

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

#### Scenario: The module still withholds a dependency
- **WHEN** the module is added to the module set
- **THEN** its withholding argument is recorded here, and the dependency it withholds is unreachable from
  every other module by compile error
