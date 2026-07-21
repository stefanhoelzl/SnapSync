# apns-push-sender Specification

## Purpose

> Full replacement of the Purpose section (one sentence added naming the platform seam; no
> requirement changes). Apply by hand at archive time and diff.

The backend's APNs client: token-based ES256 provider authentication (no certificate to rotate),
environment-selected host, and a **silent** background push delivered over HTTP/2 to one device token.

Silent — `content-available`, no alert, no sound — because the push exists to *wake* a member device so it
can pull new photos, never to interrupt the user. Delivery is reported **per token and best-effort**: APNs
can reject an individual token (expired, unregistered) without that being a failure of the fan-out, so the
sender surfaces each outcome rather than collapsing them into one verdict. APNs is the **iOS binding of
the platform-neutral wake-a-member need**; a future Android client would bind FCM behind the same
per-token, best-effort sender seam, with the caller (`event-notify-endpoint`) unchanged.

The APNs signing key (`APNS_PRIVATE_KEY`) is one of the backend's two environment **secrets**, fail-closed at startup; the key id, team id, and topic are source constants (`backend-deployment`). The caller that decides
*when* to send is `event-notify-endpoint`.

Decision record: `changes/archive/2026-07-05-push-notification-infra`.
