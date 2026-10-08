# Proposal

## Why

While a text sheet's action is running — a rename, a problem report — the sheet is promised to stay open and refuse
dismissal (capability `sync-status`, "Text entry sheets stay usable while typing"). Only its cancel button keeps that
promise. A swipe down, a tap outside it, or Android's back gesture slides the sheet away anyway: the app declines to
close it, but it is already gone from the screen, so a failure the request then reports — a name not accepted, no
connection — is shown on a sheet nobody can see, and the member never learns their rename did not happen.

## What Changes

- While a text sheet's action is running, swiping it down, tapping outside it and Android's back gesture leave it
  where it is, as cancel already does.
- The requirement's scenario names every way out, so each is held to the promise rather than only cancel and swipe.
- Nothing changes while the sheet is idle: every way out closes it.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `sync-status`: "Text entry sheets stay usable while typing" — the running-sheet scenario names the tap outside and
  Android's back gesture beside cancel and the swipe.

## Impact

- The design system's text prompt sheet: how it refuses a dismissal while busy, and its tests.
- The rename sheet (capability `manage-membership`) and the problem report sheet (capability `privacy-security`), its
  two users, gain the fix with no change of their own.
