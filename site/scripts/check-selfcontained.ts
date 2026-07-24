// Fail the build if the emitted site loads ANY off-origin runtime subresource (capability web-site's
// self-containment invariant). This is load-bearing for /join's privacy: a third-party script could read
// the eventId from location.hash. It scans the OUTPUT, not the source — so it catches a stray CDN font or
// analytics tag however it got in.
//
// FORBIDDEN: off-origin <script src>, <link href>, <img/<source> src|srcset, <iframe src>, and CSS
// url(...) / @import (fonts, styles, scripts, images loaded from another host).
// ALLOWED: navigational <a href="https://…"> (loads nothing), and the presigned photo URLs /join fetches
// (data in a JS string, not a subresource) — neither is matched below.
//
// Deno: `deno run --allow-read scripts/check-selfcontained.ts`.
import { walk } from "jsr:@std/fs@^1/walk";
import { fromFileUrl, relative } from "jsr:@std/path@^1";

const DIST = new URL("../dist/", import.meta.url);

// Each rule: a description + a regex whose match is an off-origin subresource. `https?://` only — relative
// and same-origin references (/_astro/…, ./…) never match.
const HTML_RULES: [string, RegExp][] = [
  ["<script src> off-origin", /<script\b[^>]*\bsrc\s*=\s*["']https?:\/\//gi],
  ["<link href> off-origin", /<link\b[^>]*\bhref\s*=\s*["']https?:\/\//gi],
  ["<img src|srcset> off-origin", /<img\b[^>]*\b(?:src|srcset)\s*=\s*["']https?:\/\//gi],
  ["<source src|srcset> off-origin", /<source\b[^>]*\b(?:src|srcset)\s*=\s*["']https?:\/\//gi],
  ["<iframe src> off-origin", /<iframe\b[^>]*\bsrc\s*=\s*["']https?:\/\//gi],
  ["off-origin ES import", /\bimport\b[^;\n]*\bfrom\s*["']https?:\/\//gi],
];
const CSS_RULES: [string, RegExp][] = [
  ["css url() off-origin", /url\(\s*["']?https?:\/\//gi],
  ["css @import off-origin", /@import\s+["']https?:\/\//gi],
];

const base = fromFileUrl(DIST);
const files: string[] = [];
for await (const entry of walk(DIST, { includeDirs: false })) files.push(entry.path);

const violations: string[] = [];
for (const path of files) {
  const rules = path.endsWith(".css")
    ? CSS_RULES
    : path.endsWith(".html")
      ? [...HTML_RULES, ...CSS_RULES]
      : null;
  if (!rules) continue;
  const text = await Deno.readTextFile(path);
  for (const [desc, re] of rules) {
    const m = text.match(re);
    if (m) violations.push(`${relative(base, path)}: ${desc} — ${[...new Set(m)].join(", ")}`);
  }
}

if (violations.length) {
  console.error("✗ off-origin subresource(s) found — the site must be self-contained (web-site):");
  for (const v of violations) console.error("  " + v);
  Deno.exit(1);
}
console.log(`✓ self-contained: no off-origin subresource across ${files.length} built file(s)`);
