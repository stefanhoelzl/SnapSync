// deno-lint-ignore-file no-console -- a command-line tool, never part of the edge script; its console is its interface.
// A LOOPBACK TLS FRONT for the migration rehearsal's local libSQL server (`docs/deployment.md`, "Gates").
//
// `bunny db migrations apply` refuses any URL that is not encrypted — it accepts `libsql://`, `https://` and
// `wss://` only, and has no flag to lift that (read out of the CLI: the check is unconditional). A local
// `sqld` speaks plain HTTP. So the rehearsal puts this in front of it, with a throwaway CA the job hands the
// CLI through `NODE_EXTRA_CA_CERTS`, and the CLI migrates a local store exactly as it migrates the deployed
// one. Measured 2026-09-29: list, apply and the drift refusal all behave as against production.
//
// Bound to 127.0.0.1 only. Response bodies arrive already decompressed from `fetch`, so the encoding headers
// are dropped — passing them through makes the CLI fail with a ZlibError (measured).
//
// Out of the bundle: `main.ts` never reaches it.

const [cert, key, port, upstream] = Deno.args;
if (!cert || !key || !port || !upstream) {
  console.error("usage: tls-proxy.ts <cert.pem> <key.pem> <port> <upstream-origin>");
  Deno.exit(1);
}

Deno.serve(
  {
    hostname: "127.0.0.1",
    port: Number(port),
    cert: Deno.readTextFileSync(cert),
    key: Deno.readTextFileSync(key),
    onListen: ({ port }) => console.log(`TLS PROXY UP ${port}`),
  },
  async (req) => {
    const u = new URL(req.url);
    const r = await fetch(upstream + u.pathname + u.search, {
      method: req.method,
      headers: req.headers,
      body: req.body,
    });
    const headers = new Headers(r.headers);
    headers.delete("content-encoding");
    headers.delete("content-length");
    return new Response(r.body, { status: r.status, headers });
  },
);
