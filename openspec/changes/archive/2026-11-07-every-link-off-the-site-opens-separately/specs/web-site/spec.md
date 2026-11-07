# Spec Delta

## MODIFIED Requirements

### Requirement: Links to other sites never replace the SnapSync page
Every link from the site to another site (App Store, Google Play, issue tracker, email, Apple's licence
terms) SHALL open separately, leaving the SnapSync page open where it was.

#### Scenario: Following the App Store link
- **WHEN** a visitor follows the App Store link from any page of the site
- **THEN** the App Store opens separately and the SnapSync page stays open

#### Scenario: Following the Google Play link
- **WHEN** a visitor follows the Google Play link from any page of the site
- **THEN** Google Play opens separately and the SnapSync page stays open

#### Scenario: Following any other link off the site
- **WHEN** a visitor follows any other link that leaves the site — to the issue tracker, to an email address, or to
  Apple's licence terms — from any page of the site
- **THEN** it opens separately and the SnapSync page stays open where it was
