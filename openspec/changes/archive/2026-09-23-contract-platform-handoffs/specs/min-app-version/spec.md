## ADDED Requirements

### Requirement: The store link is handed to the platform, and a hand-off that did not happen is reported

When the update-required state carries a store link, activating it SHALL hand that link to the platform to
open outside the app, through a call the platform answers, and the answer SHALL be recorded. A link the
platform did not open SHALL be logged at a severity that reaches crash reporting, because it leaves the user
on the one screen whose remedy just failed, with nothing on screen to say so. The answer SHALL NOT change the
screen: it already states the remedy in words.

The platform call SHALL be the one measured to open the link. On iOS the deprecated one-argument
`UIApplication.openURL(_:)` answered `false` and opened nothing for the build's own store link on iOS 26.6.2
(SE2, 2026-09-23), while `openURL(_:options:completionHandler:)` opened the App Store; the `LinkOpener`
contract's device recording pins the latter.

#### Scenario: The user taps the store button

- **WHEN** the update-required state carries a store link and the user activates it
- **THEN** the platform opens the store page, and the tap's log line records that the link was accepted

#### Scenario: The platform does not open the link

- **WHEN** the platform answers that it did not open the store link
- **THEN** the refusal is logged at a severity that reaches crash reporting, and the screen is unchanged

#### Scenario: The adapter reverts to a call the platform ignores

- **WHEN** the iOS link adapter stops asking iOS through `openURL(_:options:completionHandler:)`
- **THEN** the replay of the device recording reads `Diverged` on the next CI build
