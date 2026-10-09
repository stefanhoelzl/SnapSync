// Which device-API operation a request names, read off the generated operation table (`openapi.yaml`) — so the
// token gate and the version gate decide from the description's own `security`, never from a hand-kept copy of
// it (decision record `harden-seam-bug-classes`, D10: a copy drifts in silence).

import { OPERATIONS } from "./gen/wire.ts";

export type Operation = typeof OPERATIONS[number];

const MATCHERS = OPERATIONS.map((op) => ({
  op,
  re: new RegExp(`^${op.path.replace(/[.*+?^$()|[\]\\]/g, "\\$&").replace(/\{\w+\}/g, "[^/]+")}$`),
}));

/**
 * The operation `method path` names, or `undefined`. `path` is version-stripped (`splitVersion`); a `HEAD` names the
 * operation its `GET` does, as the router serves it.
 */
export function operationAt(method: string, path: string): Operation | undefined {
  const m = method === "HEAD" ? "GET" : method;
  return MATCHERS.find(({ op, re }) => op.method === m && re.test(path))?.op;
}

/**
 * Whether the token gate admits `method path` without a token: an operation whose `security` is open, or optional
 * (the two legacy anonymous event reads, `PENDING_CLEANUPS.md`) — the route verifies a token it is sent itself.
 * Every other device path, an unknown one included, is gated: the ungated set stays a closed list.
 */
export function isUngatedOperation(method: string, path: string): boolean {
  const security = operationAt(method, path)?.security;
  return security === "open" || security === "optional";
}

/**
 * The download redirect (decision record `changes/incremental-union`, D1): exempt from the version gate for the
 * builds that predate sending the header on it (`PENDING_CLEANUPS.md`, "Header-less download redirects").
 */
export function isDownloadRedirect(method: string, path: string): boolean {
  return operationAt(method, path)?.id === "downloadResource";
}
