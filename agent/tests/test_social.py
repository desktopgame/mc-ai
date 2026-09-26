import json
import os
import sys
import threading
import time
import unittest
from pathlib import Path
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))
from social import LocalSocialProvider, SocialBrain, SocialError
from daemon import turn


class FakeProvider:
    def __init__(self):
        self.calls = []
        self.output = "こんにちは。"

    def reply(self, messages):
        self.calls.append(messages)
        return self.output


def chat_payload(text="こんにちは", session="world-one"):
    return {"version": 1, "session": session,
            "event": {"type": "player_chat", "player": "owner", "text": "!agent chat " + text}}


class SocialTests(unittest.TestCase):
    def test_history_isolation_reset_and_no_actions(self):
        provider = FakeProvider()
        brain = SocialBrain(provider)
        self.assertEqual(turn(chat_payload("PRIVATE_TEST_MARKER_12345"), brain)["actions"], [])
        turn(chat_payload("覚えてる？"), brain)
        self.assertIn("PRIVATE_TEST_MARKER_12345", json.dumps(provider.calls[-1]))
        turn(chat_payload(session="world-two"), brain)
        self.assertNotIn("PRIVATE_TEST_MARKER_12345", json.dumps(provider.calls[-1]))
        request = chat_payload()
        request["event"]["text"] = "!agent forget"
        turn(request, brain)
        turn(chat_payload(), brain)
        self.assertNotIn("PRIVATE_TEST_MARKER_12345", json.dumps(provider.calls[-1]))

    def test_no_world_or_player_identifiers_in_prompt(self):
        provider = FakeProvider()
        payload = chat_payload()
        payload["event"]["player"] = "PRIVATE_PLAYER_IDENTIFIER"
        payload["state"] = {"private_memory": "WORLD_STATE_MARKER"}
        turn(payload, SocialBrain(provider))
        prompt = json.dumps(provider.calls[-1])
        self.assertNotIn("WORLD_STATE_MARKER", prompt)
        self.assertNotIn("PRIVATE_PLAYER_IDENTIFIER", prompt)

    def test_invalid_output_does_not_commit_history(self):
        provider = FakeProvider()
        brain = SocialBrain(provider)
        for output in [None, "", "x" * 513, "<think>private</think>", "\u00a7cBad", "a\0b"]:
            provider.output = output
            with self.assertRaises(SocialError):
                turn(chat_payload(), brain)
            self.assertFalse(brain.histories)

    def test_bounds_and_busy(self):
        provider = FakeProvider()
        brain = SocialBrain(provider)
        for i in range(10):
            turn(chat_payload(str(i)), brain)
        retained = brain.histories[("world-one", "owner")]
        self.assertGreater(len(retained), 12)  # no fixed six-turn truncation
        self.assertLessEqual(brain.budget.count(retained), brain.budget.history_budget_tokens)
        for i in range(40):
            turn(chat_payload(session=str(i)), brain)
        self.assertEqual(len(brain.histories), 32)
        brain.lock.acquire()
        try:
            with self.assertRaisesRegex(SocialError, "busy"):
                turn(chat_payload(), brain)
        finally:
            brain.lock.release()

    def test_ping_without_provider_and_chat_requires_configuration(self):
        payload = chat_payload()
        with self.assertRaises(SocialError):
            turn(payload)
        payload["event"]["text"] = "!agent ping"
        self.assertEqual(turn(payload)["say"], "pong")
        with self.assertRaises(ValueError):
            turn(chat_payload(" "), SocialBrain(FakeProvider()))


class ProviderHTTPTests(unittest.TestCase):
    def test_local_transport_auth_validation_and_timeout(self):
        state = {"mode": "ok"}

        class ModelHandler(BaseHTTPRequestHandler):
            def log_message(self, *args): pass
            def do_POST(self):
                state["auth"] = self.headers.get("Authorization")
                state["path"] = self.path
                state["request"] = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                if state["mode"] == "slow":
                    time.sleep(1.3)
                body = json.dumps({"choices": [{"finish_reason": state["mode"] if state["mode"] == "length" else "stop",
                                                 "message": {"content": "こんにちは。"}}]}).encode()
                if state["mode"] == "bad": body = b"not json"
                self.send_response(200)
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                try: self.wfile.write(body)
                except ConnectionError: pass

        server = ThreadingHTTPServer(("127.0.0.1", 0), ModelHandler)
        worker = threading.Thread(target=server.serve_forever, daemon=True)
        worker.start()
        try:
            with patch.dict(os.environ, {"MCAI_SOCIAL_API_KEY": "TEST_SECRET"}):
                provider = LocalSocialProvider({"base_url": "http://127.0.0.1:%d/v1" % server.server_port,
                                               "model": "test", "timeout_seconds": 1, "max_output_tokens": 128})
            with self.assertLogs("mcai.social", level="INFO") as logs:
                self.assertEqual(provider.reply([{"role": "user", "content": "PRIVATE_INPUT"}]), "こんにちは。")
            self.assertEqual(state["auth"], "Bearer TEST_SECRET")
            self.assertEqual(state["path"], "/v1/chat/completions")
            self.assertEqual(state["request"]["reasoning_effort"], "none")
            self.assertEqual(state["request"]["max_tokens"], 128)
            self.assertNotIn("TEST_SECRET", str(logs.output))
            self.assertNotIn("PRIVATE_INPUT", str(logs.output))
            for mode in ["bad", "length", "slow"]:
                state["mode"] = mode
                with self.assertRaises(SocialError):
                    provider.reply([])
        finally:
            server.shutdown()
            server.server_close()
            worker.join()

    def test_cloud_endpoint_is_rejected(self):
        with self.assertRaises(ValueError):
            LocalSocialProvider({"base_url": "https://example.com/v1", "model": "test"})


if __name__ == "__main__":
    unittest.main()
