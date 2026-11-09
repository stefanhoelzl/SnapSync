// THE INVITE'S PREVIEW NAME, KEPT NOWHERE (decision record `changes/seal-event-name`, D5).
//
// An encrypted event's shared invite carries its name readable as `?n=…`, before the `#k=` fragment, because a
// messenger's link-preview crawler never receives a fragment. The event page renders it into that one response
// and that is all: the service stores an encrypted event's name only sealed, so the readable copy that rides a
// page request must leave no trace in what the service keeps — its request log, its failure reports.
//
// Everything that records a request's URL goes through `withoutPreviewName`. It removes the parameter by NAME
// from any URL, not only the page's: a recorder cannot tell which route a URL will reach, and no other route
// takes an `n`.

/** The query parameter an invite's preview name rides in. */
export const PREVIEW_NAME_PARAM = "n";

/** [url] with the preview name removed — byte-identical when it carries none. Never throws. */
export function withoutPreviewName(url: string): string {
  let parsed: URL;
  try {
    parsed = new URL(url);
  } catch {
    // Not an absolute URL: drop any `n=` pair textually rather than keep it.
    return url.replace(
      /([?&])n=[^&#]*(&|(?=#)|$)/g,
      (_m, lead, tail) => (tail === "&" ? lead : ""),
    );
  }
  if (!parsed.searchParams.has(PREVIEW_NAME_PARAM)) return url;
  parsed.searchParams.delete(PREVIEW_NAME_PARAM);
  return parsed.toString();
}
