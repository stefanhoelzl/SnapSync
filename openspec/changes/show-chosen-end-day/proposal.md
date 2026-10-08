# Proposal

## Why

The joined screen moves an event's end back a day when it falls at midnight: a host who picks Tuesday 14 July, 00:00
as the end sees "… – Mon 13 Jul" under the event's name, and a member whose shared range ends at a midnight sees the
day before it in the line saying what they share. The rest of the product shows the day that was picked — the range
on the create and join screens (whole days 14–21 July read "14 Jul – 21 Jul") and the event page, whose dates are "as
the host chose them" (capability `event-site`) — so the same event reads one day shorter in the joined screen than
everywhere else, and shorter than what the host tapped.

## What Changes

- The joined screen's dates show the event's last day as the calendar day its end falls on, in the device's own
  timezone, including an end at midnight: an event from 12 July to 14 July 00:00 reads "12 Jul – 14 Jul".
- The line saying which photos the member shares shows its range the same way.
- An event that ends at the midnight after it starts is no longer shown as a same-day event with its times
  ("Today 18:00 – 00:00"); it spans two calendar days and is shown as its first and last day.
- How far the event is in its life (starts in, ends in, ended) is unchanged.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `sync-status`: "The joined screen shows how long the event lasts" — the last day shown is the day the end falls
  on, an end at midnight included.

## Impact

- The design system's date-range label the joined screen and its sharing explanation render: its midnight rule is
  removed, and its tests follow.
- No stored value, wire format or backend behaviour changes; the event page already shows the chosen day.
