# Spec Delta

## MODIFIED Requirements

### Requirement: Text entry sheets stay usable while typing

A sheet that asks for a line of text SHALL keep its field and both actions visible while the keyboard
is shown, SHALL keep confirm disabled while the text is empty or unchanged from what it opened with,
SHALL trim surrounding spaces, and SHALL show a refusal as a message rather than on the field. While its
action is running it SHALL stay open, show that it is working, and refuse both a second confirm and
dismissal.

#### Scenario: The keyboard does not cover confirm
- **WHEN** the member types into a text sheet with the keyboard up
- **THEN** the confirm and cancel actions remain visible and tappable

#### Scenario: Unchanged text cannot be submitted
- **WHEN** a sheet opens with an existing value and the member has not changed it
- **THEN** confirm is disabled

#### Scenario: A running sheet cannot be dismissed
- **WHEN** the member has confirmed and the action is still running
- **THEN** the sheet stays open showing progress, and neither cancel, swiping it away, tapping outside it nor
  Android's back gesture closes it or moves it off the screen
