# app-update-required Specification

## Purpose

Serves a host or guest whose installed SnapSync has become too old for the server. Instead of an app
that quietly stops working, they are told at first contact — plainly and with nothing else in the way —
that an update is needed, which version to install, and where to get it. The notice is never disguised
as a connection or sign-in problem, and it disappears by itself as soon as the app is served again.
Decision record: changes/archive/2026-08-27-add-v2-device-api

## Requirements

### Requirement: An outdated app is told so at first contact

When the server refuses the installed version as too old, the app SHALL replace every screen — create,
join and the joined event alike — with a full-screen notice that an update is required. The notice
SHALL appear on the app's first contact with the server, SHALL NOT be presented as a connection,
network or sign-in problem, and SHALL NOT be retried away as a temporary failure.

#### Scenario: Joined member with an outdated app
- **WHEN** a joined member opens an app version the server no longer serves
- **THEN** the update notice is shown instead of the joined screen

#### Scenario: A new install that is already outdated
- **WHEN** someone opens an outdated app for the first time and tries to create or join an event
- **THEN** they see the update notice rather than a failure or a sign-in error

### Requirement: The notice says what to install and where

The update notice SHALL name the oldest version that will work when the server states one, and
otherwise SHALL say that a newer version is needed. It SHALL offer a button that opens SnapSync's App
Store page.

#### Scenario: Minimum version named
- **WHEN** the server refuses the app and states that version 0.12 or newer is required
- **THEN** the notice says SnapSync 0.12 or newer is needed

#### Scenario: Opening the App Store
- **WHEN** the member taps the App Store button on the notice
- **THEN** SnapSync's App Store page opens

### Requirement: Nothing is shared or received while the app is outdated

While the app is refused as outdated, it SHALL NOT be able to create, join, share or receive, and it
SHALL resume all of them without any other action once it is served again.

#### Scenario: Photos wait for the update
- **WHEN** a member takes photos while their app is outdated and then updates
- **THEN** the photos taken in the event's range are shared after the update

### Requirement: The notice clears by itself

The update notice SHALL disappear, and the app SHALL return to its normal screen, as soon as a request
is served again — with no relaunch and nothing left over to dismiss.

#### Scenario: Served again
- **WHEN** an app showing the update notice is served by the server again
- **THEN** the notice disappears and the member's normal screen returns

### Requirement: A newer app is never mistaken for an older one

Version comparison SHALL treat each part of a version as a number, so that a later release is never
refused as older.

#### Scenario: Two-digit minor version
- **WHEN** the oldest served version is 0.9 and the app is version 0.10
- **THEN** the app is served
