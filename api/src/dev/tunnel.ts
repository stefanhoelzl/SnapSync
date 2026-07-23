// DEV-ONLY. A cloudflared quick tunnel, so a physical iPhone can reach the local rig.
//
// WHY A TUNNEL AT ALL. Default ATS is HTTPS-only and `Config.xcconfig` documents that no
// `NSAllowsLocalNetworking` exception ships — so a device cannot talk to `http://<laptop>:8080` under any
// circumstances. A quick tunnel yields a real HTTPS `*.trycloudflare.com` host with no account, no
// certificate work, and no device-side trust profile to install (which would need taps, and so would be
// hostile to the headless loop). `cloudflared` is already the transport in the ssh-mac runbook, so it is
// a known quantity here.
//
// ACCEPTED COST: the hostname is RANDOM PER SESSION, so the compile-time `BACKGROUND_UPLOAD_URL_BASE`
// changes every session and the dev IPA is rebuilt per session (~1 min incremental Debug on ssh-mac).
// The upgrade to a stable host — a named tunnel behind a `dev.snapsync.stho.net` CNAME in our Bunny DNS
// zone — needs no redesign here; only the value of that build setting changes.

import { join } from "@std/path";

/** Where a downloaded `cloudflared` is parked. Gitignored, alongside the host file. */
const BIN_DIR = ".localdev";

/**
 * Direct single-binary release assets. macOS ships a `.tgz` rather than a bare binary, so it is
 * deliberately absent: on Darwin we require `cloudflared` on PATH (`brew install cloudflared`) rather
 * than teaching this module to unpack archives.
 */
const RELEASE_ASSET: Record<string, string | undefined> = {
  "linux-x86_64": "cloudflared-linux-amd64",
  "linux-aarch64": "cloudflared-linux-arm64",
};

/** Resolve a usable `cloudflared`, preferring one already on PATH and downloading only if needed. */
async function resolveBinary(): Promise<string> {
  if (await onPath()) return "cloudflared";

  const asset = RELEASE_ASSET[`${Deno.build.os}-${Deno.build.arch}`];
  if (!asset) {
    throw new Error(
      `cloudflared is not on PATH and no single-binary release is known for ` +
        `${Deno.build.os}/${Deno.build.arch}. Install it (e.g. \`brew install cloudflared\`) and retry.`,
    );
  }

  const path = join(BIN_DIR, "cloudflared");
  try {
    await Deno.stat(path);
    return path;
  } catch {
    // fall through and fetch it
  }

  const url = `https://github.com/cloudflare/cloudflared/releases/latest/download/${asset}`;
  console.log(`fetching cloudflared → ${path}`);
  const res = await fetch(url);
  if (!res.ok) throw new Error(`cloudflared download failed: ${res.status} ${url}`);
  await Deno.mkdir(BIN_DIR, { recursive: true });
  await Deno.writeFile(path, new Uint8Array(await res.arrayBuffer()), { mode: 0o755 });
  return path;
}

async function onPath(): Promise<boolean> {
  try {
    const { success } = await new Deno.Command("cloudflared", {
      args: ["--version"],
      stdout: "null",
      stderr: "null",
    }).output();
    return success;
  } catch {
    return false;
  }
}

export type Tunnel = {
  /** The public origin, scheme included, e.g. `https://odd-fox-1234.trycloudflare.com`. */
  origin: string;
  /** Terminate the tunnel. */
  close(): void;
};

/**
 * Start a quick tunnel to `http://127.0.0.1:<port>` and resolve once cloudflared announces its hostname.
 *
 * Started BEFORE the local server binds, deliberately: the rig needs the public hostname to build its
 * `Config` (it becomes `s3Host`, so presigned download URLs point home), and cloudflared announces the
 * hostname without waiting for the origin to answer.
 */
export async function startTunnel(port: number): Promise<Tunnel> {
  const bin = await resolveBinary();
  const child = new Deno.Command(bin, {
    args: ["tunnel", "--no-autoupdate", "--url", `http://127.0.0.1:${port}`],
    stdout: "piped",
    stderr: "piped",
  }).spawn();

  const origin = await firstMatch(child.stderr, /https:\/\/[a-z0-9-]+\.trycloudflare\.com/);
  if (!origin) {
    child.kill();
    throw new Error("cloudflared exited before announcing a tunnel hostname");
  }
  // Keep draining both pipes: a full pipe buffer would block cloudflared mid-session, which would look
  // like the tunnel dying for no reason.
  drain(child.stdout);
  drain(child.stderr);

  return {
    origin,
    close: () => {
      try {
        child.kill();
      } catch {
        // already gone
      }
    },
  };
}

/** Read a stream until `pattern` matches, returning the match (or `null` if the stream ends first). */
async function firstMatch(
  stream: ReadableStream<Uint8Array>,
  pattern: RegExp,
): Promise<string | null> {
  const decoder = new TextDecoder();
  const reader = stream.getReader();
  let buffered = "";
  try {
    while (true) {
      const { value, done } = await reader.read();
      if (done) return null;
      buffered += decoder.decode(value, { stream: true });
      const found = buffered.match(pattern);
      if (found) return found[0];
    }
  } finally {
    reader.releaseLock();
  }
}

/** Consume and discard a stream, so the child never blocks on a full pipe. */
function drain(stream: ReadableStream<Uint8Array>): void {
  stream.pipeTo(new WritableStream()).catch(() => {});
}
