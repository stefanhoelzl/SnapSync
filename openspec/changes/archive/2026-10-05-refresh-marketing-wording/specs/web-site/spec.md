# Spec Delta

## MODIFIED Requirements

### Requirement: The site shows the real app, the same as the App Store
The landing page SHALL show screenshots of the real app taken from the same captures the App Store listing
uses, so the site and the listing always depict the same software. Each screenshot's caption SHALL be real
text rather than words baked into the image, and SHALL be the same headline the store listings show over
that screenshot, so the site and the listings also say the same thing about it.

#### Scenario: Refreshed captures reach the site
- **WHEN** the app's screenshots are refreshed for the App Store listing and the site is next published
- **THEN** the site shows the refreshed screenshots

#### Scenario: Captions are text
- **WHEN** a visitor selects a screenshot's caption, or a screen reader reaches it
- **THEN** the caption is readable text

#### Scenario: Captions match the store headlines
- **WHEN** a screenshot's headline on the store listings is changed and the site is next published
- **THEN** the site captions that screenshot with the changed headline
