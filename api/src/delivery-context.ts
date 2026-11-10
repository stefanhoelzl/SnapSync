// The DELIVERY CONTEXT (decision record `changes/delivery-record`, D8; `docs/architecture.md`, "The request log"):
// the device a request comes from, its platform and its conditions, which every app request carries in ONE header
// — `x-snapsync-context: device=<uuid>;os=ios/18.6;model=iPhone12,8;…` — for the request log only. No route reads
// it and nothing is refused for it: a key this backend does not know, or a value that does not read as its key's
// shape, is left out of the record, like an unreadable version header. One header rather than one per fact because
// it rides every operation and every background transfer, and the request log is its only reader.

import type { FieldValue } from "./request-log.ts";

/** The header every app request carries its delivery context in. */
export const CONTEXT_HEADER = "x-snapsync-context";

/** How a context value reads: a closed set, a yes/no, or a bounded token of a shape. */
type Shape = readonly string[] | "flag" | RegExp;

const UUID = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;

/** Each key the record keeps, and the shape its value must have. The key is also its field's name. */
const SHAPES: Record<string, Shape> = {
  device: UUID,
  os: /^(ios|android)\/[0-9][0-9.]{0,15}$/,
  model: /^[A-Za-z0-9 ,._()-]{1,48}$/,
  flavour: ["store", "testflight", "play-internal", "dev"],
  access: ["full", "limited", "none"],
  network: ["wifi", "cellular", "other", "none"],
  mobile_data: "flag",
  low_power: "flag",
  background: "flag",
};

/** The context's fields, in the header's order — every recognised key once, its first readable value. */
export function contextFields(header: string | undefined): Record<string, FieldValue> {
  const out: Record<string, FieldValue> = {};
  if (header === undefined) return out;
  for (const pair of header.split(";")) {
    const at = pair.indexOf("=");
    if (at < 0) continue;
    const key = pair.slice(0, at).trim();
    const value = pair.slice(at + 1).trim();
    const shape = SHAPES[key];
    if (shape === undefined || key in out) continue;
    const read = readValue(shape, value);
    if (read !== undefined) out[key] = read;
  }
  return out;
}

function readValue(shape: Shape, value: string): FieldValue | undefined {
  if (shape === "flag") return value === "1" ? true : value === "0" ? false : undefined;
  if (shape instanceof RegExp) return shape.test(value) ? value : undefined;
  return shape.includes(value) ? value : undefined;
}
