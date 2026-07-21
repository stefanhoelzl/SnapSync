import { defineConfig } from "astro/config";

// Static site (default output). Emits fingerprinted assets under `_astro/`, which the api static-proxy
// serves from the storage `site/` prefix with an immutable cache policy (capability `web-site`).
//
// `fs.allow` is widened one level so the landing page can import the committed raw captures directly from
// the repo-root `screenshots/` dir (a sibling of `site/`) — keeping those raws the single source of truth
// shared with the App Store listing, with no copy into `site/`.
export default defineConfig({
  build: { assets: "_astro" },
  vite: { server: { fs: { allow: [".."] } } },
});
