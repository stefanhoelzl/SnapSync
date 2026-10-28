# Design

## Context

See proposal.md, "Why". In short, `changes/archive/2026-10-06-per-event-storage-layout` made each event hold its own
bytes, and it changed `photo-sharing` to match. `event-album`'s scenario about a photo shared before the join still
carries the older promise.

What the app does today, measured over the integration suite's real stack: a member uploads photo A in one event with
the album off, leaves, and joins a second event whose range covers A, with the album on. The join creates the album.
On the device's first pass over the library, A gets a new upload job for the second event and is placed in its album
when that job is first queued (the same path as any photo being shared). The join's own gather does not place A.

## Goals / Non-Goals

**Goals:** the two specs agree, and the album scenario keeps its album promise, so the integration suite can claim it.

**Non-Goals:** any change to what is uploaded, when, or how often. That is `photo-sharing`'s outcome.

## Decisions

**Correct the scenario, not the app.** The rejected alternative was to make the app bring a carried-over photo into the
new album without uploading it again. Under per-event storage the second event holds no copy of the bytes, so its
other members could not receive the photo, and `photo-sharing`'s "A photo in two events is shared to each" would break.
The storage change deliberately chose one copy per event. This change only finishes stating it.

**Drop the clause; don't restate the upload rule.** An outcome lives in one spec. The scenario says nothing about
uploading, and `photo-sharing` keeps "may be uploaded again for it" and "no member … receives it twice".

## Risks / Trade-offs

- [The requirement text still says such photos are added "once the device has looked at them", which may be shortly
  after the join] → Still true: the first pass places them. No edit is needed.
