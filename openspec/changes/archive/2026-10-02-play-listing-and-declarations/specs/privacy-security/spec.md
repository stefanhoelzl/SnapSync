# Spec Delta

## MODIFIED Requirements

### Requirement: The Privacy Policy states what leaves the device
The Privacy Policy published on the site (capability `web-site`) SHALL accurately describe every kind of
data that leaves a user's phone or browser — the shared photos, the random install identifier, the
notification token, the app-integrity check, automatic failure reports and user-sent bug reports — why it
is processed, which service providers process it, how long photos are kept (capability `event-lifetime`),
and how to exercise data-protection rights. Where a kind of data goes to a different provider, or is checked
a different way, on iPhone and on Android, the policy SHALL describe each platform's case and name each
platform's provider. It SHALL be updated in the same release as any change to what leaves the device.

#### Scenario: A new kind of data starts leaving the device
- **WHEN** a release begins sending a kind of data the policy does not describe
- **THEN** the policy published with that release describes it and names the provider that receives it

#### Scenario: An Android user reads the policy
- **WHEN** someone who uses SnapSync on Android reads the Privacy Policy
- **THEN** it names the provider that issues and carries their phone's notification token and describes how their phone's app-integrity check works, as it does for an iPhone
