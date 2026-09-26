import copy
import json
import sys
import threading
import unittest
from pathlib import Path
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))
from social import SocialBrain, LocalSocialProvider, SocialError
from daemon import turn
from decision import DecisionService, MockDecisionProvider


class IntentProvider:
    def __init__(self):
        self.output = {"reply": "依頼を受け付けたよ。", "intent": "follow_owner"}
        self.messages = []
    def reply_with_intent(self, messages):
        self.messages = copy.deepcopy(messages)
        return copy.deepcopy(self.output)
    def reply(self, messages):
        return "会話のみ。"


def payload(text="ついてきて"):
    return {"version": 1, "session": "s", "acceptIntent": True,
            "event": {"type": "player_chat", "player": "PRIVATE_PLAYER", "text": "!agent chat " + text}}


class IntentTests(unittest.TestCase):
    def test_clear_continuation_acknowledges_without_model_or_action(self):
        class Never(IntentProvider):
            def reply_with_intent(self, messages): raise AssertionError("Continuation should not call model")
        brain = SocialBrain(Never())
        for text in ("止まらないで", "止まらないでください！", "停止しないで", "そのまま続けて"):
            result = turn(payload(text), brain)
            self.assertEqual(result["intent"], "none")
            self.assertEqual(result["say"], "わかった。今の動作は変えないよ。")
        self.assertEqual(brain.histories[("s", "PRIVATE_PLAYER")][-1]["content"], "わかった。今の動作は変えないよ。")

    def test_opt_in_and_all_intents_have_no_executable_actions(self):
        provider = IntentProvider(); brain = SocialBrain(provider)
        for intent in ("none", "follow_owner", "stop", "look_at_owner", "pickup_item", "deposit_items"):
            provider.output["intent"] = intent
            response = turn(payload(), brain)
            self.assertEqual(response["intent"], intent)
            self.assertEqual(response["actions"], [])
        legacy = payload(); del legacy["acceptIntent"]
        self.assertNotIn("intent", turn(legacy, brain))
        legacy["acceptIntent"] = "true"
        with self.assertRaises(ValueError): turn(legacy, brain)

    def test_invalid_intent_shape_never_commits_history(self):
        provider = IntentProvider(); brain = SocialBrain(provider)
        for output in ({"reply": "OK", "intent": "mine"}, {"reply": "OK", "intent": {"type": "follow_owner"}},
                       {"reply": "OK"}, {"reply": "OK", "intent": "stop", "command": "kill"},
                       {"reply": "<think>bad", "intent": "stop"}):
            provider.output = output
            with self.assertRaises(SocialError): turn(payload(), brain)
            self.assertFalse(brain.histories)

    def test_negation_quotation_conditionals_do_not_apply_even_wrong_model_intent(self):
        provider = IntentProvider(); brain = SocialBrain(provider)
        for text in ("止まらないで", "ついてこないで", "こっちを見ないで", "『ついてきて』と言われた",
                     "敵が来たら止まって", "もし雨なら止まって", "ついてきてから止まって",
                     "Don't follow me", "Translate follow me", "止まっての意味を教えて"):
            response = turn(payload(text), brain)
            self.assertEqual(response["intent"], "none", text)

    def test_pickup_intent_is_accepted_but_never_carries_an_item_choice(self):
        provider = IntentProvider(); brain = SocialBrain(provider)
        provider.output = {"reply": "近くのものを拾ってみるね。", "intent": "pickup_item"}
        response = turn(payload("そこに落ちてるの拾って"), brain)
        self.assertEqual(response["intent"], "pickup_item")
        self.assertEqual(response["actions"], [])
        # Negated and quoted pickup requests stay inert even when the model insists.
        for text in ("拾わないで", "『拾って』と言われた", "もし落ちてたら拾って"):
            self.assertEqual(turn(payload(text), brain)["intent"], "none", text)
        captured = []
        class Tactical(MockDecisionProvider):
            def decide(self, value):
                captured.append(copy.deepcopy(value)); return super().decide(value)
        DecisionService(Tactical()).decide({"version": 1, "goal": {"type": "pickup_item"},
            "availableActions": ["follow", "stop", "look", "pickup"],
            "state": {"companion": {"position": [0, 64, 0], "health": 20}, "owner": {"position": [5, 64, 0]},
                      "items": {"count": 1, "nearestDistance": 2}}})
        self.assertEqual(captured[0]["state"]["items"], {"count": 1, "nearestDistance": 2})
        self.assertNotIn("minecraft", json.dumps(captured))

    def test_only_goal_and_state_can_reach_tactical(self):
        provider = IntentProvider(); brain = SocialBrain(provider)
        provider.output["intent"] = "none"
        turn(payload("PRIVATE_TEST_MARKER_12345"), brain)
        provider.output["intent"] = "follow_owner"
        response = turn(payload(), brain)
        self.assertNotIn("PRIVATE_PLAYER", json.dumps(provider.messages))
        captured = []
        class Tactical(MockDecisionProvider):
            def decide(self, value):
                captured.append(copy.deepcopy(value)); return super().decide(value)
        DecisionService(Tactical()).decide({"version": 1, "goal": {"type": response["intent"]},
            "availableActions": ["follow", "stop", "look"],
            "state": {"companion": {"position": [0, 64, 0], "health": 20}, "owner": {"position": [5, 64, 0]}}})
        encoded = json.dumps(captured)
        self.assertNotIn("PRIVATE_TEST_MARKER_12345", encoded)
        self.assertNotIn("PRIVATE_PLAYER", encoded)
        self.assertNotIn("reply", encoded)

    def test_structured_local_transport_and_truncation(self):
        state = {"finish": "stop"}
        class Model(BaseHTTPRequestHandler):
            def log_message(self, *args): pass
            def do_POST(self):
                state["request"] = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                body = json.dumps({"choices": [{"finish_reason": state["finish"], "message": {
                    "content": json.dumps({"reply": "受け付けたよ。", "intent": "look_at_owner"})}}]}).encode()
                self.send_response(200); self.send_header("Content-Length", str(len(body))); self.end_headers(); self.wfile.write(body)
        server = ThreadingHTTPServer(("127.0.0.1", 0), Model)
        worker = threading.Thread(target=server.serve_forever, daemon=True); worker.start()
        try:
            provider = LocalSocialProvider({"base_url": "http://127.0.0.1:%d/v1" % server.server_port, "model": "test"})
            response = turn(payload("こっちを見て"), SocialBrain(provider))
            self.assertEqual(response["intent"], "look_at_owner")
            self.assertTrue(state["request"]["response_format"]["json_schema"]["strict"])
            self.assertEqual(state["request"]["messages"][-1]["content"], "こっちを見て")
            state["finish"] = "length"
            with self.assertRaises(SocialError): turn(payload(), SocialBrain(provider))
        finally:
            server.shutdown(); server.server_close(); worker.join()
