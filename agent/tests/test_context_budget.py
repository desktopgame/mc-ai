import copy
import json
import sys
import threading
import unittest
from contextlib import closing
from pathlib import Path
from http.client import HTTPConnection
from http.server import ThreadingHTTPServer

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))
from context_budget import ContextBudget, BudgetExceeded, Utf8Estimate
from social import SocialBrain, SocialError, LocalSocialProvider
from decision import LocalDecisionProvider
from daemon import Handler


def pair(text):
    return [{"role": "user", "content": text}, {"role": "assistant", "content": text}]


class TestCounter:
    """A deterministic counter for boundary tests, not an actual model tokenizer."""
    mode = "test_counter"
    def count(self, messages, response_format):
        return sum(len(m["content"]) for m in messages) + (20 if response_format is not None else 0)


class FakeSocial:
    def __init__(self): self.messages = None; self.fail = False
    def reply(self, messages):
        self.messages = copy.deepcopy(messages)
        if self.fail: raise SocialError("test_failure")
        return "ok"


class BudgetTests(unittest.TestCase):
    def test_config_types_capacity_output_alias_and_independence(self):
        for config in ({"context_window_tokens": True}, {"context_window_tokens": "8192"},
                       {"max_output_tokens": 2.5}, {"max_output_tokens": 0}, {"max_tokens": "256"},
                       {"context_window_tokens": 512}, {"safety_margin_tokens": -1},
                       {"context_window_tokens": 1024, "prompt_budget_tokens": 257},
                       {"prompt_budget_tokens": 10, "history_budget_tokens": 11},
                       {"max_tokens": 256, "max_output_tokens": 128}):
            with self.subTest(config=config), self.assertRaises(ValueError): ContextBudget(config, social=True)
        self.assertEqual(ContextBudget({"max_tokens": 128}).max_output_tokens, 128)
        self.assertEqual(ContextBudget({"max_tokens": 128, "max_output_tokens": 128}).max_output_tokens, 128)
        social = ContextBudget({"context_window_tokens": 65536, "prompt_budget_tokens": 8192}, social=True)
        decision = ContextBudget({"context_window_tokens": 4096, "max_output_tokens": 512})
        self.assertEqual(social.prompt_budget_tokens, 8192)
        self.assertEqual(decision.prompt_budget_tokens, 3072)
        self.assertEqual(decision.history_budget_tokens, 0)
        with self.assertRaises(ValueError): ContextBudget({"history_budget_tokens": 1})

    def test_oldest_pairs_removed_without_changing_system_or_user(self):
        budget = ContextBudget({"prompt_budget_tokens": 18, "history_budget_tokens": 16}, social=True, counter=TestCounter())
        system = {"role": "system", "content": "system"}; user = {"role": "user", "content": "new!"}
        history = pair("old!") + pair("last")
        original = copy.deepcopy(history)
        messages, retained = budget.prepare(system, history, user)
        self.assertEqual(retained, pair("last"))
        self.assertEqual(messages, [system] + pair("last") + [user])
        self.assertEqual(history, original)
        self.assertEqual(budget.count(messages), 18)  # exact boundary is allowed

    def test_history_zero_and_pair_larger_than_budget(self):
        budget = ContextBudget({"history_budget_tokens": 0}, social=True)
        self.assertEqual(budget.trim_history(pair("hello")), [])
        budget = ContextBudget({"history_budget_tokens": 3}, social=True, counter=TestCounter())
        self.assertEqual(budget.trim_history(pair("xx")), [])
        with self.assertRaises(ValueError): budget.trim_history(pair("x")[:1])

    def test_system_current_turn_and_schema_cannot_be_silently_truncated(self):
        budget = ContextBudget({"prompt_budget_tokens": 19, "history_budget_tokens": 0}, social=True, counter=TestCounter())
        system = {"role": "system", "content": "s"}; user = {"role": "user", "content": "u"}
        with self.assertRaisesRegex(BudgetExceeded, "mandatory_prompt_exceeds_budget"):
            budget.prepare(system, [], user, {"type": "json_schema"})
        with self.assertRaises(BudgetExceeded): budget.prepare(system, [], {"role": "user", "content": "x" * 20})
        with self.assertRaises(BudgetExceeded): budget.require([system, user], {"schema": {}})

    def test_estimate_counts_multibyte_text_schema_and_safe_logs(self):
        counter = Utf8Estimate()
        ascii_count = counter.count(pair("a"))
        self.assertGreater(counter.count(pair("あ")), ascii_count)
        self.assertGreater(counter.count(pair("😀")), counter.count(pair("あ")))
        self.assertGreater(counter.count(pair("a"), {"schema": "あ" * 50}), ascii_count)
        budget = ContextBudget(social=True)
        with self.assertLogs("mcai.budget", level="INFO") as logs:
            budget.require(pair("PRIVATE_BUDGET_MARKER"))
        self.assertIn("utf8_estimate", str(logs.output)); self.assertNotIn("PRIVATE_BUDGET_MARKER", str(logs.output))

    def test_failed_social_request_preserves_history_even_when_trimming_needed(self):
        provider = FakeSocial(); budget = ContextBudget({"history_budget_tokens": 300, "prompt_budget_tokens": 2048}, social=True)
        brain = SocialBrain(provider, persona="test", budget=budget)
        history = pair("old" * 100) + pair("recent")
        brain.histories["s"] = copy.deepcopy(history)
        provider.fail = True
        with self.assertRaises(SocialError): brain.chat("s", "current")
        self.assertEqual(brain.histories["s"], history)
        self.assertEqual(provider.messages[1:-1], pair("recent"))
        provider.fail = False; brain.chat("s", "current")
        self.assertLessEqual(budget.count(brain.histories["s"]), 300)
        self.assertEqual(brain.histories["s"][-2]["content"], "current")

    def test_provider_guards_apply_even_without_social_brain(self):
        class Never:
            def open(self, *args, **kwargs): raise AssertionError("Over-budget request reached network")
        social = LocalSocialProvider({"model": "test", "prompt_budget_tokens": 1, "history_budget_tokens": 0})
        social.opener = Never()
        with self.assertRaises(BudgetExceeded): social.reply(pair("test"))
        with self.assertRaises(BudgetExceeded): social.reply_with_intent(pair("test"))
        decision = LocalDecisionProvider({"model": "test", "prompt_budget_tokens": 1})
        decision.opener = Never()
        with self.assertRaises(BudgetExceeded): decision.decide({"goal": {"type": "stop"}})

    def test_counter_can_be_injected_and_cannot_mutate_payload(self):
        class MutatingCounter:
            mode = "test_adapter"
            def count(self, messages, response_format):
                messages.clear()
                if response_format is not None: response_format.clear()
                return 1
        messages = pair("keep"); schema = {"keep": True}
        budget = ContextBudget(counter=MutatingCounter())
        budget.require(messages, schema)
        self.assertEqual(messages, pair("keep")); self.assertEqual(schema, {"keep": True})

    def test_http_rejects_oversize_with_reason_and_without_model_call(self):
        provider = FakeSocial()
        brain = SocialBrain(provider, budget=ContextBudget({"prompt_budget_tokens": 1, "history_budget_tokens": 0}, social=True))
        server = ThreadingHTTPServer(("127.0.0.1", 0), Handler); server.brain = brain
        worker = threading.Thread(target=server.serve_forever, daemon=True); worker.start()
        try:
            with closing(HTTPConnection(*server.server_address, timeout=2)) as conn:
                conn.request("POST", "/v1/turn", json.dumps({"version": 1, "session": "s", "event": {
                    "type": "player_chat", "player": "p", "text": "!agent chat hello"}}), {"Content-Type": "application/json"})
                response = conn.getresponse(); body = json.loads(response.read())
                self.assertEqual(response.status, 422)
                self.assertEqual(body["error"], "mandatory_prompt_exceeds_budget")
            self.assertIsNone(provider.messages); self.assertFalse(brain.histories)
        finally:
            server.shutdown(); server.server_close(); worker.join()
