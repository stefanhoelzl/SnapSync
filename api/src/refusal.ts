// A failure reply, thrown (decision record `changes/api-request-log`, D2). A helper never sees the request's
// context: when its outcome is "answer this request with a 4xx/5xx", it throws a `Refusal` saying so, and
// `onError` (`request-log.ts`) is the one place that turns it into the response — byte-identical to what
// the route answered before — and records its fault and fields on the request's record.

import { HTTPException } from "hono/http-exception";
import type { ContentfulStatusCode } from "hono/utils/http-status";
import type { ErrorTag, FieldValue } from "./request-log.ts";

/** A server fault, as the request's record keeps it: its tag, and the detail kept under the tag. */
export type Fault = { tag: ErrorTag; detail: string };

export type RefusalOptions = {
  /** A server fault to record — absent for a refusal that is the caller's (a `400`, a `404`). */
  fault?: Fault;
  /** Facts to record (`refused=`, `rejected=`, `aborted=`). */
  fields?: Record<string, FieldValue>;
};

export class Refusal extends HTTPException {
  readonly body: string | object;
  readonly fault?: Fault;
  readonly fields: Record<string, FieldValue>;

  constructor(status: ContentfulStatusCode, body: string | object, options: RefusalOptions = {}) {
    super(status, { message: typeof body === "string" ? body : JSON.stringify(body) });
    this.body = body;
    this.fault = options.fault;
    this.fields = options.fields ?? {};
  }
}

/** Throw the refusal — for an expression position (`x ?? refuse(404, "event not found")`). */
export function refuse(
  status: ContentfulStatusCode,
  body: string | object,
  options?: RefusalOptions,
): never {
  throw new Refusal(status, body, options);
}

/** Let a `Refusal` through a `catch` that handles every other throw. */
export function rethrowRefusal(e: unknown): void {
  if (e instanceof Refusal) throw e;
}
