## ADDED Requirements

### Requirement: The page shows the app

The landing page SHALL carry screenshots of the real app, derived at build time from the committed raw
captures — the same source of truth the App Store listing is built from, so the page and the listing can
never depict different software. The images SHALL be **inlined** like every other asset, preserving the
page's self-containment (no external network request), and SHALL be built into the bundle at build time,
with no runtime filesystem read and no upstream call.

The screenshots SHALL NOT depict Apple hardware: no device frame, bezel, or other rendering of a phone
around the screen. Apple's third-party guidelines permit a depiction of Apple hardware only when it is an
actual photograph of the genuine product and not an artist's rendering, which a drawn or fetched frame is.

#### Scenario: Screenshots are present and inlined

- **WHEN** the page body is inspected
- **THEN** it contains screenshot images of the app, each referenced as an inlined `data:` URI and not from
  any external origin

#### Scenario: Serving the page with screenshots performs no upstream request

- **WHEN** `GET /` is served
- **THEN** the handler makes no request to the storage zone or to Apple, and reads no file at runtime

#### Scenario: The screenshots are the same captures the listing uses

- **WHEN** the committed raw captures are refreshed and deployed
- **THEN** the page serves images derived from those captures

### Requirement: The screenshots match the reader's theme

The page SHALL offer each screenshot in both a light and a dark rendering and SHALL select between them by
the reader's `prefers-color-scheme`, so a dark-mode reader is never shown a light-theme screenshot inside
the page's dark palette (and the inverse). Both renderings SHALL be inlined.

#### Scenario: A dark-mode reader sees dark screenshots

- **WHEN** the page is rendered by a client reporting `prefers-color-scheme: dark`
- **THEN** the dark rendering of each screenshot is displayed

#### Scenario: A light-mode reader sees light screenshots

- **WHEN** the page is rendered by a client reporting `prefers-color-scheme: light`
- **THEN** the light rendering of each screenshot is displayed

### Requirement: The screenshots are browsable without scripting

The screenshots SHALL be browsable on a narrow viewport without any script, preserving the page's
no-JavaScript property: the page SHALL ship no `<script>` tag. The screenshot region SHALL be reachable and
scrollable by keyboard and SHALL carry an accessible name. Any headline accompanying a screenshot SHALL be
real document text, not pixels baked into the image, so it is selectable and available to assistive
technology.

#### Scenario: No script is introduced

- **WHEN** the page body is inspected
- **THEN** it contains no `<script>` tag

#### Scenario: Headlines are text, not image content

- **WHEN** the page body is inspected
- **THEN** each screenshot's accompanying headline is present as document text

#### Scenario: The screenshot region is keyboard reachable

- **WHEN** the page body is inspected
- **THEN** the screenshot region is focusable and carries an accessible name
