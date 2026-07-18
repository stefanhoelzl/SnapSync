# full-stack-harness — delta for collapse-harness-onto-shared-composition

## MODIFIED Requirements

### Requirement: Dual-pane full-stack harness at `:app:desktop:run`

The full-stack harness SHALL be a Compose desktop **application** whose `main()` lives in the
`:app:desktop` module (run task `:app:desktop:run`), so `./gradlew :app:desktop:run` launches it.
It SHALL render two panes side by side: on the left, the real shared `StatusScreen` inside the
module's `PhoneFrame` via the module's `StatusPane`; on the right, a world-inspector control
panel. Since migration step 10, `:app:desktop` is the **one** desktop module: it hosts the shared
pane library (`PhoneFrame` + `StatusPane`) **and** both harness applications — the forge harness
(capability `desktop-test-harness`) runs from the same module via the `:app:desktop:runForge`
task. The full-stack `main()` SHALL compile to a class **distinct** from the forge harness's
`app.snapsync.desktop.MainKt`, so the two entry points never collide within the module.

#### Scenario: The full-stack run task opens both panes

- **WHEN** `:app:desktop:run` is launched
- **THEN** the window shows the real status screen inside the phone frame on the left and the
  world-inspector control panel on the right

#### Scenario: The forge harness is unaffected

- **WHEN** `:app:desktop:runForge` is launched after this change
- **THEN** the forge harness still opens (no entry-point-class collision), over the same
  `PhoneFrame` + `StatusPane` pane library
