import copy
import json
import os
import sys
import threading
import time
import unittest
from contextlib import closing
from http import client
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))
from decision import DecisionService, DecisionError, MockDecisionProvider, LocalDecisionProvider, sanitize
from social import SocialBrain
from daemon import Handler

FIXTURES = Path(__file__).resolve().parents[2] / "protocol" / "examples"


def request_fixture():
    return json.loads((FIXTURES / "decision-request.json").read_text())


class FixedProvider:
    def __init__(self, output): self.output = output
    def decide(self, payload): return self.output


class DecisionTests(unittest.TestCase):
    def test_mock_follow_stop_look_and_distance(self):
        service = DecisionService(MockDecisionProvider())
        payload = request_fixture()
        self.assertEqual(service.decide(payload), json.loads((FIXTURES / "decision-response.json").read_text()))
        for goal, action in [("stop", "stop"), ("look_at_owner", "look")]:
            payload["goal"]["type"] = goal
            self.assertEqual(service.decide(payload)["decision"]["action"], action)
        payload["goal"]["type"] = "follow_owner"
        for x, reason in [(1, "owner_near"), (40, "owner_out_of_range")]:
            payload["state"]["owner"]["position"][0] = x
            result = service.decide(payload)
            self.assertEqual(result["decision"], {"action": "follow", "target": "owner"} if x <= 2 else {"action": "stop"})
            self.assertEqual(result["reasonCode"], reason)
        payload["state"]["companion"]["health"] = 4
        self.assertEqual(service.decide(payload)["reasonCode"], "low_health")

    def test_invalid_inputs_never_reach_provider(self):
        class Never:
            def decide(self, payload): raise AssertionError("provider was called")
        invalid = [None, [], {}, {**request_fixture(), "version": True},
                   {**request_fixture(), "goal": {"type": "shell"}},
                   {**request_fixture(), "availableActions": ["mine"]},
                   {**request_fixture(), "availableActions": ["stop", "stop"]},
                   {**request_fixture(), "availableActions": []}]
        for value in [True, float("nan"), float("inf"), -1, 21, "20", 10**400]:
            payload = request_fixture()
            payload["state"]["companion"]["health"] = value
            invalid.append(payload)
        for payload in invalid:
            with self.subTest(payload=payload), self.assertRaises(ValueError):
                DecisionService(Never()).decide(payload)

    def test_unknown_actions_missing_parameters_and_unsafe_output_rejected(self):
        invalid = [None, [], {}, {"decision": {"action": "shell"}, "reasonCode": "goal_follow"},
                   {"decision": {"action": "follow"}, "reasonCode": "goal_follow"},
                   {"decision": {"action": "follow", "target": "private-player"}, "reasonCode": "goal_follow"},
                   {"decision": {"action": "stop", "command": "/kill"}, "reasonCode": "goal_stop"},
                   {"decision": {"action": "stop"}, "reasonCode": "free-form reasoning"},
                   {"decision": {"action": "look", "target": "owner"}, "reasonCode": "goal_look"}]
        for output in invalid:
            with self.subTest(output=output), self.assertRaises(DecisionError):
                DecisionService(FixedProvider(output)).decide(request_fixture())
        payload = request_fixture()
        payload["availableActions"] = ["look"]
        with self.assertRaises(DecisionError):
            DecisionService(MockDecisionProvider()).decide(payload)

    def test_provider_cannot_mutate_validation_context(self):
        class Mutator:
            def decide(self, payload):
                payload["state"]["companion"]["health"] = 20
                return {"decision": {"action": "follow", "target": "owner"}, "reasonCode": "goal_follow"}
        payload = request_fixture()
        payload["state"]["companion"]["health"] = 1
        with self.assertRaises(DecisionError): DecisionService(Mutator()).decide(payload)
        self.assertEqual(payload["state"]["companion"]["health"], 1)

    def test_social_context_leakage(self):
        class Social:
            def reply(self, messages): return "了解しました。"
        social = SocialBrain(Social(), persona="PRIVATE_PERSONA_MARKER")
        social.chat("private-session", "PRIVATE_TEST_MARKER_12345")
        payload = request_fixture()
        payload.update({"recent_chat": social.histories["private-session"], "persona": social.persona,
                        "private_memory": "PRIVATE_MEMORY_MARKER", "player": "PRIVATE_PLAYER_MARKER"})
        payload["goal"]["text"] = "PRIVATE_GOAL_MARKER"
        payload["state"]["companion"]["relationship"] = "PRIVATE_RELATIONSHIP_MARKER"
        payload["state"]["owner"]["name"] = "PRIVATE_PLAYER_MARKER"
        class Spy(MockDecisionProvider):
            def decide(self, value):
                self.captured = copy.deepcopy(value)
                return super().decide(value)
        spy = Spy()
        DecisionService(spy).decide(payload)
        text = json.dumps(spy.captured)
        self.assertNotIn("PRIVATE_", text)
        self.assertNotIn("persona", text)
        self.assertNotIn("recent_chat", text)
        self.assertEqual(spy.captured, sanitize(request_fixture()))

    def test_busy_rejected_and_failure_releases_lock(self):
        service = DecisionService(FixedProvider({}))
        with self.assertRaises(DecisionError): service.decide(request_fixture())
        self.assertFalse(service.lock.locked())
        service.lock.acquire()
        try:
            with self.assertRaisesRegex(DecisionError, "busy"): service.decide(request_fixture())
        finally: service.lock.release()


class DecisionHTTPTests(unittest.TestCase):
    def test_daemon_decision_endpoint_and_error(self):
        server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        server.decisions = DecisionService(MockDecisionProvider())
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            for payload, expected in [(request_fixture(), 200), ({}, 400)]:
                with closing(client.HTTPConnection(*server.server_address, timeout=2)) as conn:
                    conn.request("POST", "/v1/decision", json.dumps(payload), {"Content-Type": "application/json"})
                    response = conn.getresponse()
                    body = json.loads(response.read())
                    self.assertEqual(response.status, expected)
                    if expected == 200: self.assertFalse(body["executed"])
            server.decisions = DecisionService(FixedProvider({}))
            with closing(client.HTTPConnection(*server.server_address, timeout=2)) as conn:
                conn.request("POST", "/v1/decision", json.dumps(request_fixture()), {"Content-Type": "application/json"})
                response = conn.getresponse()
                self.assertEqual(response.status, 503)
                self.assertNotIn("decision", json.loads(response.read()))
        finally:
            server.shutdown(); server.server_close(); thread.join()

    def test_structured_transport_one_shot_and_failures(self):
        state = {"mode": "ok", "requests": []}
        class ModelHandler(BaseHTTPRequestHandler):
            def log_message(self, *args): pass
            def do_POST(self):
                state["requests"].append(json.loads(self.rfile.read(int(self.headers["Content-Length"]))))
                state["auth"] = self.headers.get("Authorization")
                output = {"decision": {"action": "follow", "target": "owner"}, "reasonCode": "goal_follow"}
                content = "broken json" if state["mode"] == "bad" else json.dumps(output)
                if state["mode"] == "slow": time.sleep(1.3)
                body = json.dumps({"choices": [{"finish_reason": "length" if state["mode"] == "length" else "stop",
                                               "message": {"content": content}}]}).encode()
                self.send_response(200)
                self.send_header("Content-Length", str(len(body)))
                try:
                    self.end_headers(); self.wfile.write(body)
                except ConnectionError: pass
        server = ThreadingHTTPServer(("127.0.0.1", 0), ModelHandler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            with patch.dict(os.environ, {"MCAI_DECISION_API_KEY": "TEST_SECRET"}):
                provider = LocalDecisionProvider({"base_url": "http://127.0.0.1:%d/v1" % server.server_port,
                                                  "model": "test", "timeout_seconds": 1})
            service = DecisionService(provider)
            payload = request_fixture()
            payload["recent_chat"] = "PRIVATE_TEST_MARKER_12345"
            with self.assertLogs("mcai.decision", level="INFO") as logs:
                for _ in range(2): service.decide(payload)
            self.assertEqual(state["auth"], "Bearer TEST_SECRET")
            self.assertNotIn("TEST_SECRET", str(logs.output))
            self.assertNotIn("PRIVATE_TEST_MARKER_12345", json.dumps(state["requests"]))
            for req in state["requests"]:
                self.assertEqual(len(req["messages"]), 2)
                self.assertEqual(req["response_format"]["type"], "json_schema")
                self.assertIs(req["response_format"]["json_schema"]["strict"], True)
            for mode in ["bad", "length", "slow"]:
                state["mode"] = mode
                with self.assertRaises(DecisionError): service.decide(payload)
        finally:
            server.shutdown(); server.server_close(); thread.join()


if __name__ == "__main__": unittest.main()
