// What the rig's FALLBACK BEARER has to supply besides a token (dev infrastructure — `main.ts` never
// imports `src/dev/`, so none of this can ship).
//
// A `devices` row is created only by `POST /attest/token` (capability `device-attestation`: a row exists
// iff the device has attested), and `PUT /api/v1/devices/<id>` — the push registration — UPDATEs that row,
// answering 401 when there is none.
//
// On a physical device that is a first-launch round-trip: the 401 drops the token, the app attests for
// real against the rig, and re-registers. ON A SIMULATOR IT IS UNRECOVERABLE — App Attest does not exist
// there, so `DeviceAttestation.refresh` returns early without attesting and the registration 401s forever.
// The rig therefore enrols the device whenever it supplies the token: a credential without the enrolment
// it implies is half a credential.
//
// Extracted from `serve.ts` for ONE reason: `serve.ts` starts a server at import time and cannot be
// imported by a test, and a path matcher that silently stops matching puts the simulator straight back to
// a permanent 401 with nothing to notice it.

/** How long a rig-supplied enrolment claims its token lives. Long enough never to expire mid-session. */
export const DEV_ATTEST_TTL_MS = 365 * 24 * 60 * 60 * 1000;

// Version-BLIND on purpose. This matcher decides whether the dev rig enrols a device before serving
// its push registration, and a device that is not enrolled gets a permanent `401` — with the rig
// answering normally, so the failure looks like a bad token rather than a stale regex. Pinning it to
// one version means the next version bump strands every local build silently, which is what `/api/v1`
// did until the device moved to `/api/v2`.
const CONFIG_ROUTE = /^\/api\/v\d+\/devices\/([0-9a-fA-F-]{36})$/;

/**
 * The device id to enrol before serving this request, or `null` when the request needs no enrolment.
 *
 * Scoped to the ONE route that reads the row. It deliberately does not match
 * `/api/vN/files/devices/<id>/…` or `/api/vN/events/<id>/devices/<id>`, which name a device but read no
 * `devices` row — enrolling on those would be harmless and would also make this matcher untestable as a
 * statement of which route actually needs it.
 */
export function enrolmentTarget(method: string, path: string): string | null {
  if (method.toUpperCase() !== "PUT") return null;
  return CONFIG_ROUTE.exec(path)?.[1] ?? null;
}
