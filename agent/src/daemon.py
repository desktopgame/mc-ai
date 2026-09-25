"""Phase 1 daemon. Python standard library only; no model or game dependency."""
import argparse
import json
import logging
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

LOG = logging.getLogger("mcai")
MAX_BODY = 8192


def turn(payload):
    if not isinstance(payload, dict) or type(payload.get("version")) is not int or payload["version"] != 1:
        raise ValueError("unsupported_version")
    event = payload.get("event")
    if not isinstance(event, dict) or event.get("type") != "player_chat":
        raise ValueError("invalid_event")
    player = event.get("player")
    if not isinstance(player, str) or not 1 <= len(player) <= 64:
        raise ValueError("invalid_player")
    if event.get("text") != "!agent ping":
        raise ValueError("unsupported_message")
    if "state" in payload and not isinstance(payload["state"], dict):
        raise ValueError("invalid_state")
    return {"version": 1, "say": "pong", "actions": []}


class Handler(BaseHTTPRequestHandler):
    def setup(self):
        super().setup()
        self.connection.settimeout(5)

    def log_message(self, format, *args):
        # Do not log request text, player identifiers, or headers.
        pass

    def respond(self, status, payload):
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)
        LOG.info("response status=%d bytes=%d", status, len(body))

    def error(self, status, code):
        self.respond(status, {"version": 1, "error": code})

    def do_POST(self):
        if self.path != "/v1/turn":
            self.error(404, "not_found")
            return
        if self.headers.get_content_type() != "application/json":
            self.error(415, "expected_json")
            return
        if self.headers.get("Transfer-Encoding"):
            self.error(400, "unsupported_transfer_encoding")
            return
        try:
            length = int(self.headers.get("Content-Length", ""))
        except ValueError:
            self.error(411, "content_length_required")
            return
        if not 0 < length <= MAX_BODY:
            self.error(413, "invalid_body_size")
            return
        try:
            raw = self.rfile.read(length)
            if len(raw) != length:
                raise ValueError("incomplete_body")
            payload = json.loads(raw.decode("utf-8"))
            response = turn(payload)
        except (ValueError, UnicodeError, RecursionError):
            self.error(400, "invalid_request")
            return
        except TimeoutError:
            self.error(408, "request_timeout")
            return
        self.respond(200, response)

    def do_GET(self):
        self.error(405, "method_not_allowed")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8766)
    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    with ThreadingHTTPServer((args.host, args.port), Handler) as server:
        LOG.info("Agent Daemon listening on %s:%d (protocol 1, fixed ping/pong)", args.host, args.port)
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            pass


if __name__ == "__main__":
    main()
