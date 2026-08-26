## ADDED Requirements

### Requirement: The scene-record completeness gate

A gate SHALL fail the build when the iOS shell's scene-mode resolution gains a second caller, or when the
scene generation it advances gains a second writer.

The shell answers the UI framework's rebuild signal from a counter it advances as it hands each scene out
(capability `ios-app-shell`). That counter describes what is actually installed **only because a single
function is the sole path by which a scene is obtained**. A second caller would either install a scene the
counter never saw or advance the counter without installing anything, and in both cases the signal would
answer for a scene other than the one on screen — which is the defect the rule exists to prevent, restored
by a different route.

The property is invisible to the compiler: the resolver is module-internal, so any call site inside the
iOS shell module is legal and silent. It is also invisible to review after the fact, because the damage
appears one activation later and on a device rather than at the call site.

The gate SHALL read source text rather than a resolved symbol model, because the shell's source set is not
on the JVM test classpath — the same constraint the Keychain-containment gate works under.

The gate SHALL state, at its failure, that a new call site is not to be allowlisted reflexively: the
question it forces is whether the new caller **installs** the scene it receives. A caller that installs
keeps the count complete and warrants widening the gate deliberately; a caller that merely inspects the
mode corrupts the count and should read it another way.

#### Scenario: The scene resolver gains a second caller
- **WHEN** any file in the iOS shell module other than the platform entry point calls the scene-mode
  resolver
- **THEN** the gate fails and names the offending callers

#### Scenario: The generation gains a second writer
- **WHEN** the scene generation is assigned anywhere other than where the scene mode is resolved
- **THEN** the gate fails, because a write with no scene handed out moves a signal the UI framework
  rebuilds on

#### Scenario: The gate is proven to fail
- **WHEN** the guard is introduced or changed
- **THEN** each of its assertions is shown to fail for the right reason against a deliberate violation, so
  a gate that can never go red is not mistaken for a property that always holds
