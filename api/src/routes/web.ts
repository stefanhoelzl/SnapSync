// The EVENT PAGE's read (capabilities `event-site`, `privacy-security`; decision record
// `changes/separate-event-page-from-device-api`): the one route a browser calls, at the ROOT, outside every
// `/api/vN` mount, so no device-API version can break the page and no device-API gate carries an exception
// for it.
//
// A BROWSER DOES NOT ATTEST (D1). Nothing can tell the genuine page's code from a script, so this read is
// authorized exactly as the page always was: by possession of the event id. It reads no token, and ignores an
// `Authorization` header rather than verifying it — a browser surface has no credential to check.
//
// ITS OWN SHAPE (D4): `[{deviceId, assetId, resources: [{role, filename, url}]}]`, what the page's zip needs
// and nothing more, so the device union's wire can change without breaking the page. Which assets it lists is
// the union's own rule (`completeAssets`), so the two cannot disagree about which photos are in the event.
//
// INLINE 1-HOUR LINKS (D3): each `url` is a presigned storage GET living `WEB_PRESIGN_EXPIRY_SECONDS`. The
// page uses its links within minutes, so the short life costs it nothing and bounds how long a photo withdrawn
// after the page loaded stays reachable (capability `privacy-security`). A zip that outlasts it re-reads.

import { Hono } from "hono";
import { completeAssets, logUnionFetch, unionPosition, unionRows } from "../db.ts";
import { validateUUID } from "../validators.ts";
import {
  gateEvent,
  NO_CACHE,
  orUpstream502,
  presignDownloadUrl,
  type RouteDeps,
  WEB_PRESIGN_EXPIRY_SECONDS,
} from "./support.ts";

/** One resource of a photo, as the event page reads it. */
type WebResource = { role: string; filename: string; url: string };
/** One photo of the event, as the event page reads it. */
type WebPhoto = { deviceId: string; assetId: string; resources: WebResource[] };

/** The exact path shape of the page's read — the one the token gate lists with the root routes (`app.ts`). */
export const WEB_PHOTOS_PATH = /^\/web\/events\/[^/]+\/photos$/;

/** The event page's read, at the root, built over `deps`. */
export function webRoutes(deps: RouteDeps): Hono {
  const { config, db, now, aws } = deps;
  const app = new Hono();

  // GET and HEAD only: HEAD answers the same status and headers with no body. A malformed id is the same
  // `404` as an absent event — the page shows its invalid view for both, and naming the difference helps
  // nobody. A read failure is `502`, never `404`: a fault must not claim the link is dead.
  app.on(["GET", "HEAD"], "/web/events/:eventId/photos", async (c) => {
    const eventId = c.req.param("eventId");
    c.header("Cache-Control", NO_CACHE); // the answer carries time-limited links
    if (!validateUUID(eventId)) return c.text("event not found", 404);
    const event = await gateEvent(db, c, eventId, "web photos");
    if (event instanceof Response) return event;

    return await orUpstream502(c, `web photos: assembly failed for event ${eventId}`, async () => {
      // The position first, as the union reads it: what the log records as this read's extent.
      const position = await unionPosition(db, eventId);
      const photos: WebPhoto[] = [];
      for (const resources of completeAssets(await unionRows(db, eventId))) {
        photos.push({
          deviceId: resources[0].deviceId,
          assetId: resources[0].assetId,
          resources: await Promise.all(resources.map(async (r) => ({
            role: r.role,
            filename: r.filename,
            url: await presignDownloadUrl(aws, config, r.path, WEB_PRESIGN_EXPIRY_SECONDS),
          }))),
        });
      }

      // Best-effort, and ANONYMOUS (capability `privacy-security`, "What a visit leaves behind"): that a
      // browser read the list, and when — no device, no trigger, nothing about the visitor.
      try {
        await logUnionFetch(db, {
          eventId,
          deviceId: null,
          trigger: null,
          from: null,
          to: position,
          served: photos.length,
          at: new Date(now()).toISOString(),
        });
      } catch (e) {
        console.error(`web photos: could not log the read of ${eventId} (best-effort): ${e}`);
      }
      return c.req.method === "HEAD" ? c.body(null) : c.json(photos);
    });
  });

  return app;
}
