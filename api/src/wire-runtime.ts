// What the generated routes (`src/gen/wire.ts`, from `openapi.yaml`) run on: one interpreter of an operation's
// declared inputs. The generator emits WHAT each operation takes and how it refuses each input; this file is the
// one place that says what those declarations MEAN, so the behaviour is read, linted and tested here rather than
// re-emitted per route.
//
// Inputs are validated in DECLARATION ORDER — the path item's parameters, the operation's, then the body's
// properties — and the first failure answers with the refusal the operation declares for that input
// (`x-snapsync-refusals`). That order is the contract's, not an accident of the code: a request wrong in two ways
// is answered for the first, as the routes always answered.

import type { Context } from "hono";
import type { z } from "zod";
import { refuse } from "./refusal.ts";

/** How an operation answers one input that fails validation: `400 <text>` unless it says otherwise. */
export type Refusal = { status: 400 | 404; text: string };

/** The `x-snapsync-*` rules a schema cannot state, as the generator read them off one input. */
export type Rules = {
  /** `x-snapsync-on-invalid: drop` — failing input is absent, never refused. */
  drop?: boolean;
  /** `x-snapsync-trim: true` — trimmed before it is validated. */
  trim?: boolean;
  /** `x-snapsync-after: <property>` — strictly later than that already-validated property. */
  after?: string;
  /** `x-snapsync-max-span` — at most `policy` seconds after the already-validated property `from`. */
  maxSpan?: { from: string; policy: string };
  /** `x-snapsync-check: <name>` — the named runtime check must hold. */
  check?: string;
};

/** One parameter of an operation: where it is read, how it validates, how it refuses. */
export type ParamSpec = {
  name: string;
  in: "path" | "query" | "header";
  /** Whether the parameter is a boolean, which a query string spells `true`/`false`. */
  boolean?: boolean;
  schema: z.ZodType;
  /** `undefined` when the operation does not validate its inputs at all (it declares no refusal). */
  refusal?: Refusal;
  rules: Rules;
  required: boolean;
};

/** One property of a JSON body. */
export type PropertySpec = {
  name: string;
  schema: z.ZodType;
  required: boolean;
  /** Whether the schema admits `null`; a `null` it does not admit reads as absent. */
  nullable: boolean;
  refusal?: Refusal;
  rules: Rules;
};

/** An operation's request body: JSON validated property by property, or raw bytes the handler streams. */
export type BodySpec =
  | { kind: "json"; refusal: Refusal; properties: PropertySpec[]; minProperties?: number }
  | { kind: "raw" };

export type OperationSpec = { params: ParamSpec[]; body?: BodySpec };

/** What validation consults beyond the request: the deployment's policy values and the runtime checks. */
export type WireContext = {
  policy: Record<string, number>;
  checks: Record<string, (value: string) => boolean>;
};

/** An operation's validated input, as its handler receives it. */
export type Input = {
  params: Record<string, unknown>;
  body: Record<string, unknown> | undefined;
};

/** The canonical-instant check behind `x-snapsync-instant`: a real instant that round-trips exactly. */
export function isInstant(value: string): boolean {
  const parsed = new Date(value);
  return !Number.isNaN(parsed.getTime()) &&
    parsed.toISOString().replace(".000Z", "Z") === value;
}

/** Validate a request against `spec`, or throw the declared refusal of the first input that fails. */
export async function validate(c: Context, spec: OperationSpec, ctx: WireContext): Promise<Input> {
  const params: Record<string, unknown> = {};
  for (const p of spec.params) {
    const value = readParam(c, p, ctx, params);
    if (value !== undefined) params[p.name] = value;
  }
  const body = spec.body?.kind === "json" ? await readBody(c, spec.body, ctx) : undefined;
  return { params, body };
}

function rawParam(c: Context, p: ParamSpec): string | undefined {
  if (p.in === "path") return c.req.param(p.name);
  if (p.in === "query") return c.req.query(p.name);
  return c.req.header(p.name);
}

function readParam(
  c: Context,
  p: ParamSpec,
  ctx: WireContext,
  seen: Record<string, unknown>,
): unknown {
  const raw = rawParam(c, p);
  if (p.refusal === undefined && !p.rules.drop) return raw;
  if (raw === undefined) return p.required ? refused(p.refusal, p.rules) : undefined;
  const typed = p.boolean ? (raw === "true" ? true : raw === "false" ? false : raw) : raw;
  return checked(typed, p.schema, p.rules, p.refusal, ctx, seen);
}

/** Parse `value` with `schema` and the rules; the refusal (or `undefined`, when dropped) if it fails. */
function checked(
  value: unknown,
  schema: z.ZodType,
  rules: Rules,
  refusal: Refusal | undefined,
  ctx: WireContext,
  seen: Record<string, unknown>,
): unknown {
  const input = rules.trim && typeof value === "string" ? value.trim() : value;
  const parsed = schema.safeParse(input);
  if (!parsed.success || !holds(parsed.data, rules, ctx, seen)) return refused(refusal, rules);
  return parsed.data;
}

function holds(
  value: unknown,
  rules: Rules,
  ctx: WireContext,
  seen: Record<string, unknown>,
): boolean {
  if (rules.check !== undefined && !ctx.checks[rules.check](String(value))) return false;
  if (rules.after !== undefined && !(String(value) > String(seen[rules.after]))) return false;
  if (rules.maxSpan !== undefined) {
    const from = Date.parse(String(seen[rules.maxSpan.from]));
    const span = Date.parse(String(value)) - from;
    if (span > ctx.policy[rules.maxSpan.policy] * 1000) return false;
  }
  return true;
}

/** A failing input: absent when it is dropped, otherwise its refusal, thrown. */
function refused(refusal: Refusal | undefined, rules: Rules): undefined {
  if (rules.drop) return undefined;
  const r = refusal ?? { status: 400, text: "invalid body" };
  return refuse(r.status, r.text);
}

async function readBody(
  c: Context,
  spec: Extract<BodySpec, { kind: "json" }>,
  ctx: WireContext,
): Promise<Record<string, unknown>> {
  let json: unknown;
  try {
    json = await c.req.json();
  } catch {
    refuse(400, "invalid body");
  }
  if (typeof json !== "object" || json === null || Array.isArray(json)) {
    refuse(spec.refusal.status, spec.refusal.text);
  }
  const raw = json as Record<string, unknown>;
  const body: Record<string, unknown> = {};
  for (const p of spec.properties) {
    const value = readProperty(raw[p.name], p, ctx, body);
    if (value !== undefined) body[p.name] = value;
  }
  if (spec.minProperties !== undefined && Object.keys(body).length < spec.minProperties) {
    refuse(spec.refusal.status, spec.refusal.text);
  }
  return body;
}

function readProperty(
  raw: unknown,
  p: PropertySpec,
  ctx: WireContext,
  seen: Record<string, unknown>,
): unknown {
  const absent = raw === undefined || (raw === null && !p.nullable);
  if (absent) return p.required ? refused(p.refusal, p.rules) : undefined;
  return checked(raw, p.schema, p.rules, p.refusal, ctx, seen);
}
