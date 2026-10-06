// Fail the build if the built event page and the api disagree on the TEMPLATE CONTRACT (capability event-site):
// `dist/join/index.html` is filled per request by the api, which replaces exactly the tokens it names in
// `api/src/routes/event-page.ts`. A token the page lacks leaves the event unnamed; a token the api does not know
// would reach a visitor as literal `%%…%%`. So: every api token present (DESCRIPTION twice — the page's description
// and og:description — every other once), and no other `%%…%%` in the page.
//
// Deno: `deno run --allow-read scripts/check-event-template.ts`.
import { TOKENS } from "../../api/src/routes/event-page.ts";

const html = await Deno.readTextFile(new URL("../dist/join/index.html", import.meta.url));
const problems: string[] = [];
for (const token of TOKENS) {
  const want = token === "DESCRIPTION" ? 2 : 1;
  const got = html.split(`%%${token}%%`).length - 1;
  if (got !== want) problems.push(`%%${token}%% appears ${got}×, expected ${want}×`);
}
const known = new Set<string>(TOKENS);
for (const m of html.matchAll(/%%([A-Z_]+)%%/g)) {
  if (!known.has(m[1])) problems.push(`%%${m[1]}%% is not a token the api fills`);
}
if (problems.length > 0) {
  console.error("event template contract broken:\n  " + problems.join("\n  "));
  Deno.exit(1);
}
console.log(`event template: all ${TOKENS.length} tokens present`);
