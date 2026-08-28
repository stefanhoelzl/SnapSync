## MODIFIED Requirements

### Requirement: A delivering archive is verified to carry the resolved deployment

Before an archive is handed on for delivery, the workflow SHALL read the deployment values back out of the
**built bundles** and SHALL fail the run when any of them disagrees with the resolution that produced it.
The check SHALL cover the app bundle **and** the nested background-upload extension bundle, and SHALL
cover the device-facing upload base, the APNs environment, the crash-reporting environment and the
crash-reporting DSN, in addition to the bundle identifier.

The check SHALL **additionally** read `BackgroundUploadURLBase` from each bundle's own `Info.plist` and SHALL fail the run unless it is non-empty and **exactly equal** to that bundle's rendered upload base. This value has a reader outside this repository — `assetsd` validates the background-upload registration insert against it (capability `ios-photokit-upload`) — so it is carried in the file that daemon opens as well as in the rendering our own code reads, and the two must not drift. The comparison SHALL be an equality, never a prefix test: a prefix test passes on an empty value, since every string starts with one, and on a value truncated at a comment delimiter. Equality is what makes it meaningful, because the two carriers compose one fact by different routes — only one of them passes through a grammar that can truncate — so agreement between them tests that grammar rather than restating the generator's output.

Reading the built bundle is what distinguishes this from a check on the generator's output. A renderer test
proves the generator emitted the intended bytes; it cannot see a grammar that reinterprets them, nor a
resource that failed to reach a bundle. Both have shipped mute builds.

The extension is a separately-built nested bundle with its own resources phase, and the on-device
verification path — sending a diagnostic dump — exercises only the **app** process. A resource present in
the app and absent from the extension would therefore look like a complete success, while the extension
uploads nowhere, registers the wrong APNs environment, and reports nothing.

The DSN SHALL be compared without being echoed into the build log.

#### Scenario: A delivering run verifies both bundles

- **WHEN** `ios-build` produces the signed Release archive
- **THEN** it reads the deployment values from the app bundle and from the extension bundle and compares
  each against the resolver's output, failing the run on any mismatch

#### Scenario: A truncated or mangled value fails the run

- **WHEN** a rendered value does not survive its grammar and reaches the bundle altered
- **THEN** the comparison fails and no archive is handed on for delivery

#### Scenario: A resource missing from one bundle fails the run

- **WHEN** the generated deployment rendering reaches the app bundle but not the extension bundle
- **THEN** the check fails naming the bundle, rather than delivering a build whose extension silently
  uploads nowhere and reports nothing

#### Scenario: An undistributed build is verified to carry no DSN

- **WHEN** the archive's discriminator names an undistributed build
- **THEN** the check asserts the DSN value is absent in both bundles

#### Scenario: The OS-read upload base agrees with the rendered one

- **WHEN** the archive is verified
- **THEN** each bundle's `Info.plist` `BackgroundUploadURLBase` is read and compared for exact equality with
  that bundle's rendered upload base, failing the run when it is absent, unresolved, truncated, or different

#### Scenario: A deleted OS-read key fails the run

- **WHEN** a bundle carries the rendered upload base but its `Info.plist` declares no
  `BackgroundUploadURLBase`
- **THEN** the check fails naming the bundle, rather than delivering a build whose extension the OS will
  refuse to register with a bare `PHPhotosErrorDomain -1`

