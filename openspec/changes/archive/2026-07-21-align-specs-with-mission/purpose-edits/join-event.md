# join-event Specification

## Purpose

> Full replacement of the Purpose section (one paragraph added on single vs. multi membership; no
> requirement changes). Apply by hand at archive time and diff.

The join gate: scanning a QR or opening an event link no longer joins silently. The app loads and
verifies the event's details, shows an explicit confirmation, and only on confirm enrolls the device with the
backend and provisions the config.

The surface is deliberately a **distinct, extensible `UiState` family** rather than a bare confirm dialog,
because joining is where a member's participation is *configured*, and those options were always going to
accumulate: the capture-date cutoff (`photo-selection-policy`), the upload/download direction
(`add-join-direction-mode`), and the per-event album opt-in (`event-album`) are all rows on this screen. A
dialog could not have grown them.

Switching events composes leave-then-join, so provisioning a different event cleanly departs the previous
one. `autoJoin` auto-confirms the gate for the device that just created the event, which has already
expressed intent.

**One active membership at a time is the current contract, not a law of the product.** Joining a
different event is a switch, and every joined surface renders *the* joined event. Concurrent
membership in several events is a **named future direction** — the backend already composes with it
(bytes are device-partitioned and event-independent, the leave cascade reference-counts a device
across surviving events, the ledger key is event-independent) — so new work SHALL NOT deepen the
single-membership assumption beyond what this spec already states, and a change that must lean on it
names it.

Decision record: `changes/archive/2026-07-06-add-event-join-confirmation`.
