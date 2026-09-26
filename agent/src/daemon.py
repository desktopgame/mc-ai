"""Agent Daemon: fixed ping plus optional local Social Brain."""
import argparse
import json
import logging
from pathlib import Path
from social import LocalSocialProvider, SocialBrain, SocialError, DEFAULT_PERSONA
from decision import DecisionService, MockDecisionProvider, LocalDecisionProvider, DecisionError
from state_cache import StateCache, SyncError
from goals import GoalManager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

LOG = logging.getLogger("mcai")
MAX_BODY = 32768


def turn(payload, brain=None):
    if not isinstance(payload, dict) or type(payload.get("version")) is not int or payload["version"] != 1:
        raise ValueError("unsupported_version")
    event = payload.get("event")
    if not isinstance(event, dict) or event.get("type") != "player_chat":
        raise ValueError("invalid_event")
    player = event.get("player")
    if not isinstance(player, str) or not 1 <= len(player) <= 64:
        raise ValueError("invalid_player")
    if "state" in payload and not isinstance(payload["state"], dict):
        raise ValueError("invalid_state")
    text = event.get("text")
    if text == "!agent ping":
        return {"version": 1, "say": "pong", "actions": []}
    if not isinstance(text, str) or not (text.startswith("!agent chat ") or text == "!agent forget"):
        raise ValueError("unsupported_message")
    session = payload.get("session")
    if not isinstance(session, str) or not 1 <= len(session) <= 128:
        raise ValueError("invalid_session")
    message = text[len("!agent chat "):].strip()
    if text != "!agent forget" and (not message or len(message) > 512):
        raise ValueError("invalid_message")
    if "acceptIntent" in payload and type(payload["acceptIntent"]) is not bool:
        raise ValueError("invalid_capability")
    if brain is None:
        raise SocialError("social_not_configured")
    if text == "!agent forget":
        brain.forget((session, player))
        reply = "この会話の履歴を消しました。"
    else:
        if payload.get("acceptIntent", False):
            result = brain.chat((session, player), message, with_intent=True)
            LOG.info("social intent=%s", result["intent"])
            return {"version": 1, "say": result["reply"], "intent": result["intent"], "actions": []}
        reply = brain.chat((session, player), message)
    return {"version": 1, "say": reply, "actions": []}


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
        try:
            self.wfile.write(body)
        except ConnectionError:
            LOG.info("response client_disconnected")
            return
        LOG.info("response status=%d bytes=%d", status, len(body))

    def error(self, status, code):
        self.respond(status, {"version": 1, "error": code})

    def do_POST(self):
        if self.path not in ("/v1/turn", "/v1/decision", "/v1/snapshot", "/v1/events", "/v1/state", "/v1/goal", "/v1/action-result"):
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
        body_limit = MAX_BODY if self.path in ("/v1/snapshot", "/v1/events") else 8192
        if not 0 < length <= body_limit:
            self.error(413, "invalid_body_size")
            return
        try:
            raw = self.rfile.read(length)
            if len(raw) != length:
                raise ValueError("incomplete_body")
            payload = json.loads(raw.decode("utf-8"))
            if self.path in ("/v1/goal", "/v1/action-result"):
                manager = getattr(self.server, "goals", None)
                if manager is None: raise DecisionError("goals_not_configured")
                response = manager.update(payload) if self.path == "/v1/goal" else manager.result(payload)
                LOG.info("goal endpoint=%s revision=%s status=%s", self.path, payload.get("goalRevision"), response.get("status", "result"))
            elif self.path in ("/v1/snapshot", "/v1/events"):
                response = self.server.states.update(payload, snapshot=self.path == "/v1/snapshot")
                LOG.info("observation kind=%s sequence=%d events=%d", self.path, response["sequence"], len(payload.get("events", [])))
            elif self.path == "/v1/state":
                response = self.server.states.view(payload)
            elif self.path == "/v1/decision":
                service = getattr(self.server, "decisions", None)
                if service is None:
                    raise DecisionError("decision_not_configured")
                if isinstance(payload, dict) and "session" in payload:
                    if "state" in payload: raise ValueError("ambiguous_decision_state")
                    cached = self.server.states.view({"version": payload.get("version"), "session": payload["session"]})
                    if cached["stale"]: raise SyncError("stale_state")
                    state = cached["state"]
                    if state["companion"] is None: raise SyncError("companion_unavailable")
                    payload = {"version": payload.get("version"), "goal": payload.get("goal"),
                               "availableActions": payload.get("availableActions"),
                               "state": {"companion": {"health": state["companion"]["health"], "position": state["companion"]["position"]},
                                         "owner": {"position": state["owner"]["position"]}}}
                response = service.decide(payload)
            else:
                response = turn(payload, getattr(self.server, "brain", None))
        except SyncError as exc:
            self.error(409, str(exc))
            return
        except DecisionError as exc:
            LOG.warning("decision failure=%s", str(exc))
            self.error(503, str(exc))
            return
        except SocialError as exc:
            LOG.warning("social failure=%s", str(exc))
            self.error(503, str(exc))
            return
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
    parser.add_argument("--config", type=Path, help="Local SocialProvider JSON config; omitted = ping only")
    parser.add_argument("--decision-config", type=Path, help="Independent mock or local DecisionProvider config")
    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    brain = None
    if args.config:
        config = json.loads(args.config.read_text(encoding="utf-8-sig"))
        if config.get("api_key_file"):
            config["api_key_file"] = str((args.config.resolve().parent / config["api_key_file"]).resolve())
        brain = SocialBrain(LocalSocialProvider(config), config.get("persona", DEFAULT_PERSONA))
    decisions = None
    if args.decision_config:
        config = json.loads(args.decision_config.read_text(encoding="utf-8-sig"))
        if config.get("api_key_file"):
            config["api_key_file"] = str((args.decision_config.resolve().parent / config["api_key_file"]).resolve())
        if config.get("provider") == "mock":
            provider = MockDecisionProvider()
        elif config.get("provider") == "local":
            provider = LocalDecisionProvider(config)
        else:
            raise ValueError("unknown decision provider")
        decisions = DecisionService(provider)
    with ThreadingHTTPServer((args.host, args.port), Handler) as server:
        server.brain = brain
        server.decisions = decisions
        server.states = StateCache()
        server.goals = GoalManager(server.states, decisions)
        LOG.info("Agent Daemon listening on %s:%d (protocol 1, social=%s)", args.host, args.port, brain is not None)
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            pass


if __name__ == "__main__":
    main()
