# photo-selection-policy Specification

## Purpose

> Full replacement of the Purpose section (one paragraph added after the floor paragraph; no
> requirement changes). Apply by hand at archive time and diff.

**Which of my photos enter this event.** One policy, applied at one place, deciding what a member contributes.

It has two halves, and they answer different questions.

**When was it taken?** A per-device, per-membership **capture-date cutoff**, chosen at join: the member
contributes only photos taken from a moment they pick onward. Without it, joining an event shares a device's
entire photo library — and because every uploaded asset enters the event union, every other member downloads
it too. The joiner faced an all-or-nothing choice between sharing years of unrelated photos and not joining.
The cutoff makes contribution scopeable, and it defaults to the event's **start date** (`startsAt` — the
instant the host sets at creation, capability `event-creation`), which is almost always what the member means.

The event's start is also a **floor** beneath the member's choice: the persisted cutoff is
`max(chosen, startsAt)`, clamped once at join. So the host bounds the event's contents from below — no photo
taken before the event began can ever be uploaded to it — while the member remains free to choose any later
cutoff. The event can only ever **narrow** a membership's scope, never widen it beyond what the member picked,
and the value being committed is visible on the join surface before the confirm.

The cutoff is deliberately a **lower bound only** — there is no capture-date end bound, and none is
planned. "The event is over" is owned server-side by the event's **lifetime** (capability
`event-limits`): grace keeps existing members' late uploads landing for a short window past `endsAt`,
then expiry deletes the event, and uploads stop because their destination stops existing — never
because this policy excluded a late photo. A photo taken after the celebration wound down but inside
the event's window is admitted on the same reasoning as admit-on-doubt below: visible and harmless,
where a second end-bound mechanism would add a new silent way for a real event photo to fail.

**What is it?** The cutoff bounds *when*, but says nothing about *what*. Inside the event window a camera roll
also accumulates screenshots, memes, and media received over messaging apps and the browser — none of which
anyone at the event took, and all of which would otherwise upload to the event and land on every other
member's phone. The **origin exclusions** subtract those.

The exclusions can only *subtract*, never *infer*: PhotoKit exposes no "this device's camera took this" flag
on any iOS through 26. So the policy excludes what is certainly not a capture and **admits on doubt** — a
stray uploaded meme is visible and harmless, while an event photo that silently fails to upload is a failure
of the product's core promise that the user cannot even notice, let alone fix.

**One policy gates both directions of the member's own contribution** — the byte upload and the manifest
listing — so a photo excluded from the upload cannot leak into the event through the listing. It also scopes
the own-device status total, so the screen counts what this device intends to share rather than everything it
owns.

Decision record: `changes/archive/2026-07-06-add-join-date-cutoff` (the cutoff);
`changes/archive/2026-07-14-add-event-start-date` (the event start as the cutoff's default **and** floor);
`changes/archive/…-add-photo-selection-policy` (the origin exclusions, and this capability's rename);
`changes/archive/…-align-specs-with-mission` (the lower-bound-only decision — expiry owns the end).
