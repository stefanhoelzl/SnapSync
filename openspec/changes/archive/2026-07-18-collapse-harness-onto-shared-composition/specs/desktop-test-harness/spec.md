# desktop-test-harness — delta for collapse-harness-onto-shared-composition

## MODIFIED Requirements

### Requirement: Dual-pane harness layout

The forge harness SHALL be a Compose desktop **application** in the `:app:desktop` module (run task
`:app:desktop:runForge`; folded in from the deleted `:app:desktop:ui` at migration step 10) that
renders two panes side by side: on the left, the real shared status screen inside a fixed
phone-sized frame (~390×844 with a visible bezel) so it is previewed at ship proportions; on the
right, a control panel. The phone frame and the status-screen composition wiring (construct
`StatusContainerHost` from the injected seams → render the shared `StatusScreen` inside the frame)
SHALL live in the same module's shared pane library (`PhoneFrame` + `StatusPane`), which the
full-stack harness reuses — the two harnesses cannot drift in how they mount the real screen. The
phone frame's content MUST be the same status-screen composable that the iOS app will ship — not a
copy.

#### Scenario: Harness opens with both panes
- **WHEN** the desktop application is launched
- **THEN** the window shows the status screen inside a phone-sized frame on the left and the control panel on the right

#### Scenario: Phone frame keeps ship proportions
- **WHEN** the desktop window is resized
- **THEN** the phone frame retains its fixed ~390×844 content size
