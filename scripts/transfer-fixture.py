#!/usr/bin/env python3
"""The loopback peer the transfer contracts exchange bytes with (`docs/architecture.md`, "An adapter bound per
compilation target is real for the clauses it runs there"). It is a clause INPUT: each route answers what the clause
chose, encoded in the route's last segment, so what the contract states is how the app's transports behave given an
answer, never how a backend behaves.

    scripts/transfer-fixture.py --port 8123 --log build/sim-contracts/transfer-fixture.log

The grammar is `TransferFixture` in :test:contracts — change both or neither:

    GET|PUT /<Contract>/<CLAUSE_ID>/<name>/s200-n1024-len    answer 200; a GET gets 1024 bytes, Content-Length declared
    GET|PUT /<Contract>/<CLAUSE_ID>/<name>/s404-n0-nolen     answer 404; a GET's body carries no Content-Length
    GET     /<Contract>/<CLAUSE_ID>/<name>/s200-n64-short    answer 200 declaring 64 bytes, send 32, close
    GET|PUT /<Contract>/<CLAUSE_ID>/<name>/hold              never answer (closed after HOLD_SECONDS)
    GET /_landed/<route>                                     200 {"contentType": …} if a 2xx PUT landed there, else 404
    GET /_health                                             200, once the server is up

A GET body is the byte pattern `i % 251`, so a clause can compare staged bytes. Every response says
`Cache-Control: no-store`: the simulator's default session caches, and a cached answer is not a transfer. Every
request is logged, one line each, for the job's evidence. Stdlib only; dev infrastructure, loads nothing on Linux.
"""
import argparse
import json
import re
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

SEGMENT = re.compile(r"s(\d{3})-n(\d+)-(len|nolen|short)")
# Longer than any clause waits, so a held transfer ends by its clause's cancel, not by this; bounded so a transfer a
# clause leaked cannot hold the job.
HOLD_SECONDS = 60

landed = {}
lock = threading.Lock()


def answer_of(path):
    segment = path.split("?", 1)[0].rsplit("/", 1)[-1]
    if segment == "hold":
        return "hold"
    m = SEGMENT.fullmatch(segment)
    if not m:
        return None
    return int(m.group(1)), int(m.group(2)), m.group(3)


def body(length):
    return bytes(i % 251 for i in range(length))


class Handler(BaseHTTPRequestHandler):
    # HTTP/1.0: every response closes its connection, which is what lets a GET without Content-Length end.
    protocol_version = "HTTP/1.0"

    def log_message(self, fmt, *args):
        self.server.log.write("%s %s\n" % (time.strftime("%H:%M:%S"), fmt % args))
        self.server.log.flush()

    def _send(self, status, payload=b"", declare=True, content_type="application/octet-stream"):
        self.send_response(status)
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Type", content_type)
        if declare:
            self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def do_GET(self):
        if self.path == "/_health":
            return self._send(200, b"ok")
        if self.path.startswith("/_landed/"):
            with lock:
                hit = landed.get(self.path[len("/_landed"):])
            if hit is None:
                return self._send(404)
            return self._send(200, json.dumps(hit).encode(), content_type="application/json")
        answer = answer_of(self.path)
        if answer is None:
            return self._send(400, b"not in the TransferFixture grammar")
        if answer == "hold":
            time.sleep(HOLD_SECONDS)
            return
        status, length, mode = answer
        if mode == "short":
            self.send_response(status)
            self.send_header("Cache-Control", "no-store")
            self.send_header("Content-Length", str(length))
            self.end_headers()
            self.wfile.write(body(length)[: length // 2])
            return
        self._send(status, body(length), declare=mode == "len")

    def do_PUT(self):
        size = int(self.headers.get("Content-Length") or 0)
        self.rfile.read(size)
        answer = answer_of(self.path)
        if answer is None:
            return self._send(400, b"not in the TransferFixture grammar")
        if answer == "hold":
            time.sleep(HOLD_SECONDS)
            return
        status = answer[0]
        if 200 <= status < 300:
            with lock:
                landed[self.path] = {"contentType": self.headers.get("Content-Type")}
        self._send(status)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--log", required=True)
    args = parser.parse_args()
    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    server.daemon_threads = True
    server.log = open(args.log, "a")
    server.serve_forever()


if __name__ == "__main__":
    sys.exit(main())
