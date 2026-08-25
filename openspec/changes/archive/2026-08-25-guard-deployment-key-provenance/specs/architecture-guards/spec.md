## ADDED Requirements

### Requirement: No reader is left behind on a moved deployment key

A guard SHALL assert that no file in the repository, other than the resolver itself, extracts from the
committed `Config.xcconfig` a build setting that `Config.xcconfig` does not itself assign. The guarded set
SHALL be derived from that file's own assignments rather than from any enumeration of keys that have
moved, so a key is covered whichever rendering it moves to — the build-settings fragment, the bundled
property list, or one not yet invented — and covered from the moment it moves.

The guard SHALL scan the repository's text surfaces, including `.claude/skills/`, because the reader that
motivated it was a skill rather than source code. It SHALL match a setting being **read out of** that
file, and SHALL NOT match a file merely naming both, so that `Config.xcconfig`'s own header comment
documenting what the generated renderings carry, and the design records discussing the split, remain
passable. An extraction SHALL be attributed to the file it actually reads, resolved from the extraction
site itself — the filename it names, or the variable it reads — and never from mere proximity to a
filename mentioned elsewhere.

The guard SHALL fail loudly rather than vacuously: it SHALL assert that the scanned file set is non-empty
and that the committed file's assignments parsed non-empty, so that a parse regression cannot make it pass
by finding nothing to guard.

A reader left behind does not raise anywhere. The file it reads still exists and is still readable; it
simply no longer assigns the setting, so the extraction yields the empty string and the reader proceeds.
Where that value composes a signing identity, the result is a validly signed binary claiming the wrong
identity — which every downstream validity check passes, and which the existing wildcard guard cannot see,
because absence of a wildcard is not presence of the right prefix.

#### Scenario: A read of a moved setting from the committed file fails the build

- **WHEN** any file other than the resolver extracts from `Config.xcconfig` a setting it does not assign
- **THEN** the guard fails the build, naming the file and the setting

#### Scenario: A newly moved key is covered without editing the guard

- **WHEN** a key moves out of `Config.xcconfig` into ANY rendering, and a reader still extracts it from
  `Config.xcconfig`
- **THEN** the guard fails, without that key having been named anywhere in the guard

#### Scenario: A setting the committed file still assigns is not a violation

- **WHEN** a reader extracts from `Config.xcconfig` a setting `Config.xcconfig` assigns
- **THEN** the guard passes, because that read resolves a real value

#### Scenario: Documenting the split is not a read

- **WHEN** a file names a moved key and `Config.xcconfig` without extracting the one from the other — as
  `Config.xcconfig`'s own header comment does
- **THEN** the guard passes

#### Scenario: An extraction from another file is not attributed by proximity

- **WHEN** a file extracts a setting from some other file, near an unrelated mention of `Config.xcconfig`
- **THEN** the guard passes, because provenance is resolved from the extraction site, not from nearness

#### Scenario: The guard is not vacuous

- **WHEN** the scanned file set is empty, or the committed file's assignments fail to parse
- **THEN** the guard fails, rather than passing while inspecting nothing

#### Scenario: The guard re-runs when any scanned surface changes

- **WHEN** a file in any surface the guard scans is edited, and the guards are run
- **THEN** the guard task re-runs rather than reporting up-to-date

The scanned set and the task's declared inputs SHALL name the same surfaces. A guard whose subject is
wider than its declared inputs stops running silently — which is indistinguishable from a guard that
runs and finds nothing.
