# Design

## Context

See proposal.md — Why. Capacity is stamped on each `events` row at creation from
`deployments/components/policy.json` and enforced by one conditional insert (`api/src/db.ts`), which
is exact under concurrency. Nothing about that changes; what bounds the number now is what else grows
with the member count N.

Everything else measured by member count (surveyed for this change):

| what | growth | bound that bites |
|---|---|---|
| v2 byte upload / manifest publish: wake the other members | ≈ N + 5 subrequests (storage PUT, ~3–4 DB calls, the token query, N−1 pushes, maybe an FCM OAuth fetch) | Bunny Edge's **50 subrequests per request** → ≈ N 45 |
| v1 `/notify` (pre-0.4 builds; exempt from the version gate) | ≈ 2N + 2 (one token read **per member**, then N pushes) | the same 50 → ≈ N 24 |
| v2 fan-out timeout (`FANOUT_TIMEOUT_MS`, 4 s) | parallel over one HTTP/2 connection | none at 40; its comment cites "capacity is 10" |
| union read `GET /events/:id/files` | O(N·P) rows + one presign each, unpaginated | ≈ 6 MB JSON at 40 × 200 photos; Edge's 30 s CPU / 128 MB far off |
| app reconcile per wake, downloads per member | O(N·P), (N−1)·P | linear cost only |
| pushes, egress | O(N²·P) total; pushes collapsed per event by APNs/FCM | cost only |
| early close | needs every active member's final | a straggler is likelier to hold it to the 3-day fallback |

A subrequest over the limit fails as a fetch error that the push sender records as `failed`: the write
still stands, and the members it missed simply aren't woken (they catch up on foreground or the hourly
heartbeat). So crossing the wall loses wakes **silently**, never data.

## Goals / Non-Goals

**Goals:**
- New events admit 40 devices, with every member woken on every upload.
- The number stops being contract; the documentation says what bounds it.

**Non-Goals:**
- Paginating or incrementalizing the union read.
- Batching or queueing the push fan-out beyond one request (the work 100+ members would need).
- Changing already-created events' capacity.
- Touching the mission's named future: capacity stays the paid-tier boundary.

## Decisions

### D1: 40, set by the subrequest wall rather than by cost

The v2 fan-out costs about N + 5 subrequests, so the wall sits near 45. 40 leaves headroom for one or
two extra calls on that path (e.g. the occasional FCM token fetch, a future statement) without a
fan-out redesign. *Alternatives*: 25 (stays under v1's unfixed wall — superseded by D2); 100+
(needs fan-out batching across requests and likely a paginated union — a project, not a policy change);
20 (needlessly small).

### D2: v1 notify reads all tokens in one statement

`POST /api/v1/events/:id/notify` today enumerates active members and then calls `readPushToken` once per
member — 2N + 2 subrequests. It switches to the existing `pushTokensForEvent(db, eventId)` with no
exclusion, which joins active memberships to registered tokens in one statement: same recipients (active
members with a complete registration, the caller included), N + 3 subrequests. The per-member helper is
deleted if nothing else uses it. *Alternatives*: accept degraded wakes for pre-0.4 builds in large events;
retire v1 notify (a separate question — v1 is deliberately exempt from the version gate).

### D3: The spec states no number

The cap is a technical bound set by the service, so `event-lifetime` says "a limited number of devices
set by the service" and keeps only what a user relies on: departed devices count, a leaver rejoins in its
own place, the limit is exact, and a full event says so (`join-event`, unchanged). The value and its
reason live in `policy.json` and `docs/`; a future raise under the wall is then a config change with
no spec change. *Alternative*: keep the literal 40 in the spec — testable, but it re-asserts a
technical value as a product promise.

### D4: The wall is documented, not tested

The ceiling is written beside `eventCapacity` in `docs/deployment.md` (and in the Edge limits list),
and the v2 timeout comment points at it. No test pins `eventCapacity` against the fan-out's
subrequest budget. *Alternative*: a budget test in `api/test` — rejected by the owner for now.

### D5: The mock follows the policy

`BackendMock.DEFAULT_CAPACITY` mirrors the real api's rules and moves to 40 with the policy, so the
JVM rig and the world harness admit what production admits.

## Risks / Trade-offs

- [A later raise past ~45 brings back silent wake loss, with nothing failing] → D4's documentation
  beside the value; a raise is a deliberate edit of that file.
- [The union read grows to several MB and is re-read on every wake, under iOS's background CPU clamp]
  → Accepted unmeasured; pagination is a follow-up if large events show slow reconciles.
- [A new statement on the upload/publish path eats into the 5-device headroom] → the docs name the
  budget; the comment on the fan-out timeout points at it.
- [Larger events close early less often] → the 3-day fallback still bounds it; the retention promise
  is unchanged.

## Migration Plan

Deploy the backend. Events created afterwards are stamped 40; existing events keep 10 until they close.
Rollback: revert `policy.json`; events created in between keep 40, which the v2 path still serves.
