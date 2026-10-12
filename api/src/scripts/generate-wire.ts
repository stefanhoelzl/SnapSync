// deno-lint-ignore-file no-console -- a build step: it reports what it wrote, and why it refused.
//
// The backend's half of the wire generation (`openapi.yaml`): reads the one hand-written description and writes
// `src/gen/wire.ts` — the zod schema and TypeScript type of every component, each operation's declared inputs
// (validated by `src/wire-runtime.ts`), the typed `Handlers` every operation must have, `registerRoutes` that
// mounts them at the description's own method and path, and the security table the token gate reads. GENERATED,
// never committed (`.gitignore`): `deno task gen`, chained in front of every task that needs it.
//
// It REFUSES rather than guesses: an `x-snapsync-*` name outside the closed vocabulary, a schema keyword it does
// not translate, a reference outside this document, or an operation that validates an input it declares no
// refusal for each fail the build, naming the place.

import { parse } from "@std/yaml";
import { dirname, fromFileUrl, join } from "@std/path";

type Node = Record<string, unknown>;

const API_DIR = join(dirname(fromFileUrl(import.meta.url)), "..", "..");
export const DESCRIPTION_PATH = join(API_DIR, "openapi.yaml");
export const OUTPUT_PATH = join(API_DIR, "src", "gen", "wire.ts");

/** The closed `x-snapsync-*` vocabulary (`openapi.yaml`, "Conventions"). */
export const VOCABULARY: ReadonlySet<string> = new Set([
  "x-snapsync-outcome",
  "x-snapsync-instant",
  "x-snapsync-after",
  "x-snapsync-max-span",
  "x-snapsync-check",
  "x-snapsync-gate",
  "x-snapsync-export",
  "x-snapsync-transport",
  "x-snapsync-refusals",
  "x-snapsync-on-invalid",
  "x-snapsync-trim",
]);

/** The schema keywords translated to zod; any other one is refused. Annotations are accepted and ignored. */
const TRANSLATED = new Set([
  "type",
  "properties",
  "required",
  "items",
  "enum",
  "const",
  "pattern",
  "minLength",
  "maxLength",
  "minItems",
  "minimum",
  "maximum",
  "minProperties",
  "oneOf",
  "allOf",
  "discriminator",
  "$ref",
]);
const ANNOTATIONS = new Set(["description", "title", "examples", "default", "format"]);

const METHODS = ["get", "put", "post", "delete", "options", "head", "patch"] as const;

class Refused extends Error {}

function fail(where: string, why: string): never {
  throw new Refused(`openapi.yaml ${where}: ${why}`);
}

/** Every `x-snapsync-*` key in `node`, anywhere, must be in the vocabulary. */
export function checkVocabulary(node: unknown, where = "#"): void {
  if (Array.isArray(node)) {
    node.forEach((n, i) => checkVocabulary(n, `${where}/${i}`));
    return;
  }
  if (typeof node !== "object" || node === null) return;
  for (const [key, value] of Object.entries(node)) {
    if (key.startsWith("x-snapsync-") && !VOCABULARY.has(key)) {
      fail(where, `\`${key}\` is not in the closed x-snapsync vocabulary`);
    }
    // A refusals map is keyed by INPUT names, and a header input may be named `x-snapsync-…` itself.
    if (key === "x-snapsync-refusals") continue;
    checkVocabulary(value, `${where}/${key}`);
  }
}

/** The component name a local `$ref` names. */
function refName(ref: string, kind: string): string {
  const prefix = `#/components/${kind}/`;
  if (!ref.startsWith(prefix)) fail(ref, `only references into components/${kind} are translated`);
  return ref.slice(prefix.length);
}

class Description {
  constructor(readonly doc: Node) {}

  component(kind: string, name: string): Node {
    const found = ((this.doc.components as Node)[kind] as Node | undefined)?.[name];
    if (found === undefined) fail(`#/components/${kind}/${name}`, "is referenced but not defined");
    return found as Node;
  }

  /** `node`, or what its `$ref` names when it is a Reference Object. */
  resolve(node: Node, kind: string): Node {
    return typeof node.$ref === "string" ? this.component(kind, refName(node.$ref, kind)) : node;
  }

  /** The schema `node` is, following a `$ref` — and keeping the `x-snapsync-*` siblings of the reference. */
  schemaRules(node: Node): Node {
    const target = typeof node.$ref === "string" ? this.resolve(node, "schemas") : {};
    return { ...target, ...node };
  }
}

// ── zod ──────────────────────────────────────────────────────────────────────────────────────────────

function literal(value: unknown): string {
  return JSON.stringify(value);
}

function checkKeywords(schema: Node, where: string): void {
  for (const key of Object.keys(schema)) {
    if (TRANSLATED.has(key) || ANNOTATIONS.has(key) || key.startsWith("x-")) continue;
    fail(where, `the schema keyword \`${key}\` is not translated`);
  }
}

/** The zod expression for `schema`; components are referenced by name. */
export function zodOf(schema: Node | boolean, where: string): string {
  if (schema === true || (typeof schema === "object" && Object.keys(schema).length === 0)) {
    return "z.unknown()";
  }
  if (schema === false) return "z.never()";
  checkKeywords(schema, where);
  if (Array.isArray(schema.type)) return nullableType(schema, where);
  const instant = schema["x-snapsync-instant"] === true ? ".refine(isInstant)" : "";
  if (typeof schema.$ref === "string") {
    return refName(schema.$ref, "schemas") + instant;
  }
  return base(schema, where) + instant;
}

/** `type: [<one type>, "null"]` — the one type, nullable; any other list is refused. */
function nullableType(schema: Node, where: string): string {
  const types = schema.type as string[];
  const one = types.filter((t) => t !== "null");
  if (types.length !== 2 || one.length !== 1) fail(where, 'a type list is only `[<type>, "null"]`');
  return `${zodOf({ ...schema, type: one[0] }, where)}.nullable()`;
}

function base(schema: Node, where: string): string {
  if (schema.const !== undefined) return `z.literal(${literal(schema.const)})`;
  if (Array.isArray(schema.enum)) return `z.enum(${literal(schema.enum)})`;
  if (Array.isArray(schema.oneOf)) return oneOf(schema, where);
  if (Array.isArray(schema.allOf)) {
    const parts = (schema.allOf as Node[]).map((s, i) => zodOf(s, `${where}/allOf/${i}`));
    return parts.reduce((a, b) => `z.intersection(${a}, ${b})`);
  }
  return typed(schema, where);
}

function oneOf(schema: Node, where: string): string {
  const members = schema.oneOf as Node[];
  const nonNull = members.filter((m) => m.type !== "null");
  if (nonNull.length === 1 && members.length === 2) {
    return `${zodOf(nonNull[0], `${where}/oneOf/0`)}.nullable()`;
  }
  const parts = members.map((s, i) => zodOf(s, `${where}/oneOf/${i}`));
  const discriminator = schema.discriminator as Node | undefined;
  return discriminator
    ? `z.discriminatedUnion(${literal(discriminator.propertyName)}, [${parts.join(", ")}])`
    : `z.union([${parts.join(", ")}])`;
}

function typed(schema: Node, where: string): string {
  switch (schema.type) {
    case "string":
      return string(schema);
    case "integer":
      return number(schema, "z.number().int()");
    case "number":
      return number(schema, "z.number()");
    case "boolean":
      return "z.boolean()";
    case "null":
      return "z.null()";
    case "array":
      return array(schema, where);
    case "object":
      return object(schema, where);
    case undefined:
      return "z.string()";
    default:
      return fail(where, `the type ${literal(schema.type)} is not translated`);
  }
}

function string(schema: Node): string {
  let out = "z.string()";
  if (typeof schema.minLength === "number") out += `.min(${schema.minLength})`;
  if (typeof schema.maxLength === "number") out += `.max(${schema.maxLength})`;
  if (typeof schema.pattern === "string") out += `.regex(new RegExp(${literal(schema.pattern)}))`;
  return out;
}

function number(schema: Node, start: string): string {
  let out = start;
  if (typeof schema.minimum === "number") out += `.min(${schema.minimum})`;
  if (typeof schema.maximum === "number") out += `.max(${schema.maximum})`;
  return out;
}

function array(schema: Node, where: string): string {
  let out = `z.array(${zodOf(schema.items as Node, `${where}/items`)})`;
  if (typeof schema.minItems === "number") out += `.min(${schema.minItems})`;
  return out;
}

function object(schema: Node, where: string): string {
  const required = new Set((schema.required as string[] | undefined) ?? []);
  const props = Object.entries((schema.properties as Node | undefined) ?? {}).map(([name, s]) => {
    const z = zodOf(s as Node, `${where}/properties/${name}`);
    return `${literal(name)}: ${required.has(name) ? z : `${z}.optional()`}`;
  });
  let out = `z.object({ ${props.join(", ")} })`;
  if (typeof schema.minProperties === "number") {
    out += `.refine((o) => Object.keys(o).length >= ${schema.minProperties})`;
  }
  return out;
}

/** The names `schema` references, for ordering the declarations. */
function dependencies(schema: unknown, out = new Set<string>()): Set<string> {
  if (Array.isArray(schema)) schema.forEach((s) => dependencies(s, out));
  else if (typeof schema === "object" && schema !== null) {
    const ref = (schema as Node).$ref;
    if (typeof ref === "string" && ref.startsWith("#/components/schemas/")) {
      out.add(ref.slice("#/components/schemas/".length));
    }
    Object.values(schema).forEach((v) => dependencies(v, out));
  }
  return out;
}

function ordered(schemas: Node): string[] {
  const done: string[] = [];
  const visit = (name: string, path: string[]) => {
    if (done.includes(name)) return;
    if (path.includes(name)) fail(`#/components/schemas/${name}`, "references itself");
    for (const dep of dependencies(schemas[name])) visit(dep, [...path, name]);
    done.push(name);
  };
  Object.keys(schemas).forEach((n) => visit(n, []));
  return done;
}

// ── operations ───────────────────────────────────────────────────────────────────────────────────────

type Security = "gated" | "optional" | "open";

type Op = {
  id: string;
  method: string;
  path: string;
  security: Security;
  transport: string | undefined;
  node: Node;
  params: Node[];
};

function securityOf(op: Node, root: Node): Security {
  const requirements = (op.security ?? root.security ?? []) as Node[];
  if (requirements.length === 0) return "open";
  return requirements.some((r) => Object.keys(r).length === 0) ? "optional" : "gated";
}

function operations(d: Description): Op[] {
  const out: Op[] = [];
  for (const [path, item] of Object.entries(d.doc.paths as Node)) {
    const pathItem = item as Node;
    const shared = ((pathItem.parameters as Node[] | undefined) ?? []).map((p) =>
      d.resolve(p, "parameters")
    );
    for (const method of METHODS) {
      const op = pathItem[method] as Node | undefined;
      if (op === undefined) continue;
      const own = ((op.parameters as Node[] | undefined) ?? []).map((p) =>
        d.resolve(p, "parameters")
      );
      out.push({
        id: String(op.operationId),
        method: method.toUpperCase(),
        path,
        security: securityOf(op, d.doc),
        transport: op["x-snapsync-transport"] as string | undefined,
        node: op,
        params: [...shared, ...own],
      });
    }
  }
  return out;
}

function refusalOf(raw: unknown): string | undefined {
  if (raw === undefined) return undefined;
  if (typeof raw === "string") return `{ status: 400, text: ${literal(raw)} }`;
  const r = raw as Node;
  return `{ status: ${Number(r.status)}, text: ${literal(r.text)} }`;
}

function rulesOf(schema: Node, where: string): string {
  const rules: string[] = [];
  if (schema["x-snapsync-on-invalid"] !== undefined) {
    if (schema["x-snapsync-on-invalid"] !== "drop") {
      fail(where, "x-snapsync-on-invalid is only `drop`");
    }
    rules.push("drop: true");
  }
  if (schema["x-snapsync-trim"] === true) rules.push("trim: true");
  if (typeof schema["x-snapsync-after"] === "string") {
    rules.push(`after: ${literal(schema["x-snapsync-after"])}`);
  }
  const span = schema["x-snapsync-max-span"] as Node | undefined;
  if (span !== undefined) {
    rules.push(`maxSpan: { from: ${literal(span.from)}, policy: ${literal(span.policy)} }`);
  }
  if (typeof schema["x-snapsync-check"] === "string") {
    rules.push(`check: ${literal(schema["x-snapsync-check"])}`);
  }
  return `{ ${rules.join(", ")} }`;
}

/** An input's refusal, required when the operation validates its inputs and the input is not dropped. */
function inputRefusal(op: Op, name: string, rules: Node, where: string): string {
  const refusals = op.node["x-snapsync-refusals"] as Node | undefined;
  if (refusals === undefined) return "undefined";
  const r = refusalOf(refusals[name]);
  if (r === undefined && rules["x-snapsync-on-invalid"] !== "drop") {
    fail(where, `${op.id} validates \`${name}\` but declares no refusal for it`);
  }
  return r ?? "undefined";
}

type Emitted = { spec: string; inputType: string; schemas: string[] };

/** An input's zod schema, hoisted to a named constant so its type can be read off it. */
function hoist(op: Op, input: string, z: string, schemas: string[]): string {
  if (/^[A-Z]\w*$/.test(z)) return z;
  const name = `${op.id}_${input.replace(/\W/g, "_")}`;
  schemas.push(`const ${name} = ${z};`);
  return name;
}

function paramSpecs(
  d: Description,
  op: Op,
  schemas: string[],
): { specs: string[]; types: string[] } {
  const specs: string[] = [];
  const types: string[] = [];
  for (const p of op.params) {
    if (p["x-snapsync-gate"] !== undefined) continue;
    const where = `${op.id} parameter ${p.name}`;
    const schema = d.schemaRules({ ...(p.schema as Node), ...pick(p, "x-snapsync-") });
    const z = hoist(op, String(p.name), zodOf(p.schema as Node, where), schemas);
    const isBoolean = schema.type === "boolean";
    specs.push(
      `{ name: ${literal(p.name)}, in: ${literal(p.in)}, boolean: ${isBoolean}, schema: ${z}, ` +
        `refusal: ${inputRefusal(op, String(p.name), schema, where)}, rules: ${
          rulesOf(schema, where)
        }, required: ${p.required === true} }`,
    );
    types.push(`${literal(p.name)}${p.required ? "" : "?"}: z.infer<typeof ${z}>`);
  }
  return { specs, types };
}

/** Only the `x-snapsync-*` keys of `node`. */
function pick(node: Node, prefix: string): Node {
  return Object.fromEntries(Object.entries(node).filter(([k]) => k.startsWith(prefix)));
}

function bodySpec(d: Description, op: Op, schemas: string[]): { spec: string; type: string } {
  const requestBody = op.node.requestBody as Node | undefined;
  if (requestBody === undefined) return { spec: "undefined", type: "undefined" };
  const content = d.resolve(requestBody, "requestBodies").content as Node;
  const json = content["application/json"] as Node | undefined;
  if (json === undefined) return { spec: `{ kind: "raw" }`, type: "undefined" };
  const schema = d.resolve(json.schema as Node, "schemas");
  const where = `${op.id} body`;
  if (schema.type !== "object") fail(where, "a JSON body is an object of properties");
  const required = new Set((schema.required as string[] | undefined) ?? []);
  const refusals = (op.node["x-snapsync-refusals"] as Node | undefined) ?? {};
  const bodyRefusal = refusalOf(refusals.body) ??
    fail(where, `${op.id} declares no \`body\` refusal`);
  const props: string[] = [];
  const types: string[] = [];
  for (const [name, raw] of Object.entries(schema.properties as Node)) {
    const node = raw as Node;
    const rules = d.schemaRules(node);
    const z = hoist(op, `body_${name}`, zodOf(node, `${where}/${name}`), schemas);
    const nullable = Array.isArray(rules.oneOf) &&
      (rules.oneOf as Node[]).some((m) => m.type === "null");
    props.push(
      `{ name: ${literal(name)}, schema: ${z}, required: ${
        required.has(name)
      }, nullable: ${nullable}, ` +
        `refusal: ${inputRefusal(op, name, rules, `${where}/${name}`)}, rules: ${
          rulesOf(rules, `${where}/${name}`)
        } }`,
    );
    types.push(`${literal(name)}${required.has(name) ? "" : "?"}: z.infer<typeof ${z}>`);
  }
  const min = typeof schema.minProperties === "number"
    ? `, minProperties: ${schema.minProperties}`
    : "";
  return {
    spec: `{ kind: "json", refusal: ${bodyRefusal}, properties: [\n      ${
      props.join(",\n      ")
    },\n    ]${min} }`,
    type: `{ ${types.join("; ")} }`,
  };
}

function emitOperation(d: Description, op: Op): Emitted {
  const schemas: string[] = [];
  const { specs, types } = paramSpecs(d, op, schemas);
  const body = bodySpec(d, op, schemas);
  const name = op.id[0].toUpperCase() + op.id.slice(1);
  return {
    spec: `  ${op.id}: {\n    params: ${list(specs)},\n    body: ${body.spec},\n  },`,
    inputType: `export type ${name}Input = { params: { ${
      types.join("; ")
    } }; body: ${body.type} };`,
    schemas,
  };
}

/** A multi-line array literal of `items`, or `[]`. */
function list(items: string[]): string {
  return items.length === 0 ? "[]" : `[\n      ${items.join(",\n      ")},\n    ]`;
}

function honoPath(path: string): string {
  return path.replace(/\{(\w+)\}/g, ":$1");
}

// ── the module ───────────────────────────────────────────────────────────────────────────────────────

export function generate(doc: Node): string {
  checkVocabulary(doc);
  const d = new Description(doc);
  const schemas = (doc.components as Node).schemas as Node;
  const ops = operations(d);
  const out: string[] = [
    "// GENERATED by src/scripts/generate-wire.ts from openapi.yaml — never edit, never commit.",
    "// deno-lint-ignore-file",
    'import { z } from "zod";',
    'import type { Context, Hono } from "hono";',
    'import { isInstant, type OperationSpec, validate, type WireContext } from "../wire-runtime.ts";',
    "",
    "// No validator compiled through `new Function`: the edge runtime need not allow it.",
    "z.config({ jitless: true });",
    "",
  ];
  for (const name of ordered(schemas)) {
    out.push(
      `export const ${name} = ${zodOf(schemas[name] as Node, `#/components/schemas/${name}`)};`,
    );
    out.push(`export type ${name} = z.infer<typeof ${name}>;`);
  }
  const emitted = ops.map((op) => emitOperation(d, op));
  out.push("", "// Each operation's own input schemas.");
  emitted.forEach((e) => out.push(...e.schemas));
  out.push("", "/** Each operation's declared inputs, as `validate` reads them. */");
  out.push(`export const SPECS: Record<OperationId, OperationSpec> = {`);
  emitted.forEach((e) => out.push(e.spec));
  out.push("};", "");
  emitted.forEach((e) => out.push(e.inputType));
  out.push("", `export type OperationId = ${ops.map((o) => literal(o.id)).join(" | ")};`);
  out.push(
    "",
    "/** Every operation of the description: how it is reached, and what the token gate makes of it. */",
    "export const OPERATIONS = [",
  );
  for (const op of ops) {
    out.push(
      `  { id: ${literal(op.id)}, method: ${literal(op.method)}, path: ${literal(op.path)}, ` +
        `security: ${literal(op.security)}, transport: ${literal(op.transport ?? "app")} },`,
    );
  }
  out.push("] as const;", "");
  out.push(
    "/** One handler per operation, each receiving its validated input. */",
    "export interface Handlers {",
  );
  for (const op of ops) {
    const name = op.id[0].toUpperCase() + op.id.slice(1);
    out.push(`  ${op.id}(c: Context, input: ${name}Input): Response | Promise<Response>;`);
  }
  out.push("}", "");
  out.push(
    "/** Mount every operation on `app` at its declared method and path, validating its input first. */",
    "export function registerRoutes(app: Hono, handlers: Handlers, ctx: WireContext): void {",
  );
  for (const op of ops) {
    const name = op.id[0].toUpperCase() + op.id.slice(1);
    out.push(
      `  app.on(${literal(op.method)}, ${literal(honoPath(op.path))}, async (c) => ` +
        `await handlers.${op.id}(c, (await validate(c, SPECS.${op.id}, ctx)) as unknown as ${name}Input));`,
    );
  }
  out.push("}", "");
  return out.join("\n");
}

if (import.meta.main) {
  try {
    const doc = parse(await Deno.readTextFile(DESCRIPTION_PATH)) as Node;
    const code = generate(doc);
    await Deno.mkdir(dirname(OUTPUT_PATH), { recursive: true });
    await Deno.writeTextFile(OUTPUT_PATH, code);
    console.log(`wrote ${OUTPUT_PATH}`);
  } catch (e) {
    if (!(e instanceof Refused)) throw e;
    console.error(e.message);
    Deno.exit(1);
  }
}
