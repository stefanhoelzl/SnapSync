// The backend's wire generator (`src/scripts/generate-wire.ts`): it translates the description it is given, and it
// REFUSES — naming the place — what it would otherwise have to guess.

import { assertEquals, assertStringIncludes, assertThrows } from "@std/assert";
import { parse } from "@std/yaml";
import { DESCRIPTION_PATH, generate } from "../src/scripts/generate-wire.ts";
import { OPERATIONS } from "../src/gen/wire.ts";
import { isDownloadRedirect, isUngatedOperation, operationAt } from "../src/route-security.ts";

type Node = Record<string, unknown>;

const description = async (): Promise<Node> =>
  parse(await Deno.readTextFile(DESCRIPTION_PATH)) as Node;

/** The smallest description with one validated operation, for the refusals below to break. */
function minimal(op: Node, schemas: Node = {}): Node {
  return {
    openapi: "3.1.2",
    info: { title: "t", version: "1" },
    paths: { "/things/{id}": { put: { operationId: "putThing", ...op } } },
    components: { schemas },
  };
}

const ID = { name: "id", in: "path", required: true, schema: { type: "string" } };

Deno.test("the repository's description generates", async () => {
  assertStringIncludes(generate(await description()), "export function registerRoutes(");
});

Deno.test("an x-snapsync name outside the closed vocabulary is refused, naming where", () => {
  const doc = minimal({ "x-snapsync-retries": 3, responses: {} });
  const e = assertThrows(() => generate(doc));
  assertStringIncludes((e as Error).message, "x-snapsync-retries");
  assertStringIncludes((e as Error).message, "#/paths//things/{id}/put");
});

Deno.test("an operation that validates an input it declares no refusal for is refused", () => {
  const doc = minimal({ parameters: [ID], "x-snapsync-refusals": {}, responses: {} });
  assertStringIncludes(
    (assertThrows(() => generate(doc)) as Error).message,
    "putThing validates `id` but declares no refusal for it",
  );
});

Deno.test("a schema keyword the generator does not translate is refused", () => {
  const doc = minimal({ responses: {} }, { Odd: { type: "string", multipleOf: 2 } });
  assertStringIncludes((assertThrows(() => generate(doc)) as Error).message, "`multipleOf`");
});

Deno.test("a reference outside components is refused", () => {
  const doc = minimal({ responses: {} }, { Far: { $ref: "other.yaml#/Thing" } });
  assertStringIncludes((assertThrows(() => generate(doc)) as Error).message, "only references");
});

Deno.test("every operation's security reaches the gate as the description declares it", () => {
  const security = Object.fromEntries(OPERATIONS.map((o) => [o.id, o.security]));
  assertEquals(security.challenge, "open");
  assertEquals(security.mintToken, "open");
  assertEquals(security.getEvent, "optional");
  assertEquals(security.eventFiles, "optional");
  assertEquals(security.downloadResource, "open");
  for (const id of ["createEvent", "changeEvent", "joinEvent", "publishManifest", "putDevice"]) {
    assertEquals(security[id], "gated", id);
  }
});

Deno.test("the gate's matcher: method and path name one operation; HEAD names the GET", () => {
  const E = "7a3f9c21-0000-4000-8000-000000000001";
  assertEquals(operationAt("PATCH", `/events/${E}`)?.id, "changeEvent");
  assertEquals(operationAt("HEAD", `/events/${E}/files`)?.id, "eventFiles");
  assertEquals(isUngatedOperation("GET", `/events/${E}`), true);
  assertEquals(isUngatedOperation("PATCH", `/events/${E}`), false);
  assertEquals(isUngatedOperation("GET", "/attest/token"), false); // no such operation: gated
  assertEquals(isDownloadRedirect("GET", `/events/${E}/files/devices/${E}/A/primary`), true);
  assertEquals(isDownloadRedirect("PUT", `/events/${E}/files/devices/${E}/A/primary`), false);
});
