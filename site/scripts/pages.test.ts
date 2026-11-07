// What the BUILT pages carry (`dist/`, from `npm run build`): the markup the site decides, as a browser receives it.
// What a browser then does with it — rendering, colour scheme, keyboard focus, opening a tab — is the browser's.
// Deno: `deno test --allow-read scripts/` (npm run check:unit, after the build in CI's site-build job).
import { assert, assertEquals } from "jsr:@std/assert@^1";
import { walkSync } from "jsr:@std/fs@^1/walk";
import { playOfferFor } from "../src/lib/play.ts";

const SITE = new URL("../", import.meta.url);
const DIST = new URL("dist/", SITE);

function read(path: string): string {
  try {
    return Deno.readTextFileSync(new URL(path, DIST));
  } catch (e) {
    throw new Error(`dist/${path} is not there — run \`npm run build\` first (${e})`);
  }
}

const deployment = JSON.parse(Deno.readTextFileSync(new URL("src/deployment.json", SITE)));
const headlines = JSON.parse(
  Deno.readTextFileSync(new URL("../metadata/screenshots/en-US.json", SITE)),
).headlines;

const landing = () => read("index.html");
const eventPage = () => read("join/index.html");
/** Every built page, by its path under dist/. */
function pages(): Map<string, string> {
  const found = new Map<string, string>();
  for (const entry of walkSync(DIST, { exts: [".html"], includeDirs: false })) {
    found.set(entry.path.slice(DIST.pathname.length), Deno.readTextFileSync(entry.path));
  }
  assert(
    found.has("index.html") && found.has("join/index.html"),
    `the build holds ${[...found.keys()]}`,
  );
  return found;
}

/** The `<a …>` tags of a piece of HTML, each as its attribute map. */
function links(html: string): Map<string, string>[] {
  return [...html.matchAll(/<a\s([^>]*)>/g)].map(
    ([, attrs]) => new Map([...attrs.matchAll(/([\w-]+)="([^"]*)"/g)].map(([, k, v]) => [k, v])),
  );
}

/** The event page's two views: what a valid invite shows, and what an invalid or expired one shows. */
function views(html: string): { app: string; invalid: string } {
  const app = html.indexOf('id="app"');
  const invalid = html.indexOf('id="invalid"');
  const end = html.indexOf("</main>");
  assert(app > 0 && invalid > app && end > invalid, "the event page's views moved");
  return { app: html.slice(app, invalid), invalid: html.slice(invalid, end) };
}

/** The Google Play links the deployment's offer puts on a page (none before Google Play). */
function playHrefs(): string[] {
  const offer = playOfferFor(deployment);
  if (offer.kind === "none") return [];
  if (offer.kind === "listing") return [offer.listingUrl];
  return [offer.groupUrl, offer.optInUrl, offer.listingUrl];
}

const hrefs = (html: string) => links(html).map((a) => a.get("href") ?? "");

// @Verifies(spec = "event-site", requirement = "A shared invite previews its event", scenario = "A preview shows no photo")
Deno.test("the event page's preview image is the app icon, never an event's photo", () => {
  const image = eventPage().match(/<meta property="og:image" content="([^"]*)"/)?.[1] ?? "";
  assert(new URL(image).pathname.startsWith("/_astro/Icon-"), image);
  assert(!image.includes("%%"), "no token the api could fill with a photo");
});

// @Verifies(spec = "event-site", requirement = "The page always offers the download and the app", scenario = "Both actions are offered")
Deno.test("the event page offers the zip download and the app", () => {
  const { app } = views(eventPage());
  assert(/<button[^>]*id="download"[^>]*>Download all photos \(zip\)<\/button>/.test(app));
  assert(hrefs(app).includes(deployment.appStoreUrl));
});

// @Verifies(spec = "event-site", requirement = "The page always offers the download and the app", scenario = "The tester steps are offered for an invalid link too")
Deno.test(
  "an invalid link's view offers the App Store and the same Google Play offer, and no download",
  () => {
    const { app, invalid } = views(eventPage());
    assert(hrefs(invalid).includes(deployment.appStoreUrl));
    for (const href of playHrefs()) {
      assert(hrefs(app).includes(href), href);
      assert(hrefs(invalid).includes(href), href);
    }
    assert(!invalid.includes('id="download"'));
  },
);

// @Verifies(spec = "web-site", requirement = "A public landing page describes SnapSync", scenario = "A visitor opens the site")
Deno.test("the landing page describes SnapSync and links the App Store", () => {
  const html = landing();
  assert(/<title>SnapSync\b[^<]*<\/title>/.test(html));
  assert(
    html.includes('id="how"') && html.includes('id="what"'),
    "the sections describing SnapSync",
  );
  assert(hrefs(html).includes(deployment.appStoreUrl));
});

// @Verifies(spec = "web-site", requirement = "Privacy Policy, Terms of Use and support are published on the site", scenario = "The policies can be linked directly")
Deno.test("the Privacy Policy and the Terms of Use each have their own anchor", () => {
  const html = landing();
  assert(/id="privacy"[^>]*>[\s\S]*?<h2>Privacy Policy<\/h2>/.test(html));
  assert(/id="terms"[^>]*>[\s\S]*?<h2>Terms of Use<\/h2>/.test(html));
});

// @Verifies(spec = "web-site", requirement = "Privacy Policy, Terms of Use and support are published on the site", scenario = "Support is reachable from any page")
Deno.test("every page links Privacy, Terms, Support and a contact email", () => {
  for (const [path, html] of pages()) {
    const all = hrefs(html);
    assert(all.includes("/#privacy"), path);
    assert(all.includes("/#terms"), path);
    assert(
      all.some((h) => h.startsWith("https://github.com/") && h.endsWith("/issues")),
      path,
    );
    assert(
      all.some((h) => h.startsWith("mailto:")),
      path,
    );
  }
});

const captions = () =>
  [...landing().matchAll(/<figcaption>([^<]*)<\/figcaption>/g)].map((m) => m[1]);

// @Verifies(spec = "web-site", requirement = "The site shows the real app, the same as the App Store", scenario = "Captions are text")
Deno.test("each screenshot's caption is text beside the image", () => {
  assertEquals(captions().length, 3);
  for (const caption of captions()) assert(caption.trim().length > 0);
});

// @Verifies(spec = "web-site", requirement = "The site shows the real app, the same as the App Store", scenario = "Captions match the store headlines")
Deno.test("the captions are the store screenshots' headlines, in order", () => {
  assertEquals(captions(), [headlines.create, headlines.joining, headlines.in_sync]);
});

// @Verifies(spec = "web-site", requirement = "The site carries the app icon", scenario = "The tab shows the app icon")
Deno.test("every page names the app icon as its tab icon, and the build holds it", () => {
  for (const [path, html] of pages()) {
    const icon = html.match(/<link rel="icon"[^>]*href="([^"]*)"/)?.[1] ?? "";
    assert(icon.startsWith("/_astro/Icon-") && icon.endsWith(".png"), `${path}: ${icon}`);
    assert(Deno.statSync(new URL(icon.slice(1), DIST)).isFile, icon);
  }
});

/** Every link on every page whose href starts with [prefix] opens in a tab of its own. */
function opensSeparately(prefix: string) {
  let seen = 0;
  for (const [path, html] of pages()) {
    for (const a of links(html).filter((a) => (a.get("href") ?? "").startsWith(prefix))) {
      seen++;
      assertEquals(a.get("target"), "_blank", `${path}: ${a.get("href")}`);
      assert((a.get("rel") ?? "").includes("noopener"), `${path}: ${a.get("href")}`);
    }
  }
  assert(seen > 0, `no ${prefix} link on any page`);
}

// @Verifies(spec = "web-site", requirement = "Links to other sites never replace the SnapSync page", scenario = "Following the App Store link")
// The page's part: the link asks for a tab of its own. The browser opens it.
Deno.test("every App Store link opens in a tab of its own", () =>
  opensSeparately("https://apps.apple.com/"),
);

// @Verifies(spec = "web-site", requirement = "Links to other sites never replace the SnapSync page", scenario = "Following the Google Play link")
// The page's part: the link asks for a tab of its own. The browser opens it.
Deno.test("every Google Play link opens in a tab of its own", () => {
  if (playHrefs().length === 0) return; // no Google Play offer before publication: nothing to open
  opensSeparately("https://play.google.com/");
});

/** Whether [href] leaves the site: an email address, or a web address on another host than the site's own. */
function leavesSite(href: string): boolean {
  if (href.startsWith("mailto:")) return true;
  if (!/^https?:\/\//.test(href)) return false;
  return new URL(href).host !== deployment.domain;
}

// @Verifies(spec = "web-site", requirement = "Links to other sites never replace the SnapSync page", scenario = "Following any other link off the site")
// The page's part: every link that leaves the site — not only the kinds named today — asks for a tab of its own.
Deno.test("every link off the site opens in a tab of its own", () => {
  const seen: string[] = [];
  for (const [path, html] of pages()) {
    for (const a of links(html).filter((a) => leavesSite(a.get("href") ?? ""))) {
      const href = a.get("href")!;
      seen.push(href);
      assertEquals(a.get("target"), "_blank", `${path}: ${href}`);
      assert((a.get("rel") ?? "").includes("noopener"), `${path}: ${href}`);
    }
  }
  for (const kind of [
    "https://github.com/stefanhoelzl/SnapSync/issues",
    "mailto:",
    "https://www.apple.com/legal/",
  ]) {
    assert(
      seen.some((href) => href.startsWith(kind)),
      `no ${kind} link on any page: ${seen}`,
    );
  }
});

// @Verifies(spec = "privacy-security", requirement = "The web pages track no one", scenario = "A visitor loads the landing page")
// The page's part: it runs no script and loads nothing from another site. The api sets no cookie (landing.test.ts).
// @Verifies(spec = "web-site", requirement = "The landing page works without JavaScript and by keyboard", scenario = "JavaScript is off")
// With no script on the page, nothing on it waits for one; the screenshots are plain images.
Deno.test("the landing page runs no script and loads nothing from another site", () => {
  const html = landing();
  assert(!/<script\b/i.test(html), "a script on the landing page");
  const origin = `https://${deployment.domain}/`;
  const loaded = [
    ...html.matchAll(/<(?:img|source|script|iframe|video|audio)\b[^>]*\b(?:src|srcset)="([^"]*)"/g),
    ...html.matchAll(/<link\b(?![^>]*\brel="(?:canonical|alternate)")[^>]*\bhref="([^"]*)"/g),
  ].flatMap((m) => m[1].split(",").map((s) => s.trim().split(/\s+/)[0]));
  assert(loaded.length > 0, "the scan found the page's own images and styles");
  for (const url of loaded) {
    assert(url.startsWith("/") || url.startsWith(origin) || url.startsWith("data:"), url);
  }
});

/** The Privacy Policy as a reader sees it: the built section's text, tags dropped, whitespace collapsed. */
function policy(): string {
  const html = landing();
  const start = html.indexOf('id="privacy"');
  const end = html.indexOf("</section>", start);
  assert(start > 0 && end > start, "the Privacy Policy section moved");
  return html
    .slice(start, end)
    .replace(/<[^>]+>/g, " ")
    .replace(/&#39;|&rsquo;/g, "'")
    .replace(/\s+/g, " ");
}

// @Verifies(spec = "privacy-security", requirement = "The Privacy Policy states what leaves the device", scenario = "An Android user reads the policy")
Deno.test(
  "the policy names Android's push provider and how Android's integrity check works",
  () => {
    const text = policy();
    assert(
      /Google, through Firebase Cloud Messaging, on Android/.test(text),
      "the push token's provider on Android",
    );
    assert(
      /on Android through a key that the phone's secure hardware certifies/.test(text),
      "Android's check",
    );
    assert(/on iPhone through Apple App Attest/.test(text), "as it does for an iPhone");
  },
);

// @Verifies(spec = "privacy-security", requirement = "The Privacy Policy states what leaves the device", scenario = "A user reads how their phone is identified")
Deno.test(
  "the policy says how the device ID is made on each platform, what it is not linked to, and that it can stay",
  () => {
    const text = policy();
    assert(
      /on iPhone a random one; on Android one derived from the identifier Android gives SnapSync alone/.test(
        text,
      ),
    );
    assert(/not linked to your identity or to any other app/.test(text));
    assert(/can stay the same if you delete SnapSync and install it again/.test(text));
  },
);
