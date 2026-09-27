"""Real HTTP C1 lifecycle and focused checks for the scenario test infrastructure."""
import copy
import json
import sys
import unittest
from pathlib import Path

TESTS = Path(__file__).resolve().parent
sys.path.insert(0, str(TESTS.parent / "src"))
sys.path.insert(0, str(TESTS))

from scenario_support.fixture import TestDaemonFixture
from scenario_support.peer import ScriptedForgePeer
from scenario_support.runner import OPS, ScenarioFormatError, ScenarioRunner, equal, pointer

C1 = TESTS / "scenarios" / "collect-drop-c1.json"


class ScenarioTests(unittest.TestCase):
    def test_c1_real_http_repeats_with_fresh_services(self):
        epochs = set()
        for repeat in range(3):
            with self.subTest(repeat=repeat):
                runner = ScenarioRunner()
                captured = runner.run_file(C1)
                epochs.add(captured["open"]["body"]["daemonEpoch"])
                summary = json.loads((runner.artifact_dir / "summary.json").read_text(encoding="utf-8"))
                self.assertEqual(summary["status"], "passed")
                self.assertEqual(summary["stepsCompleted"], 19)
        self.assertEqual(len(epochs), 3)

    def test_fixture_empty_clock_wiring_and_teardown_on_failure(self):
        fixture = TestDaemonFixture()
        with self.assertRaisesRegex(RuntimeError, "intentional"):
            with fixture:
                server, worker = fixture.server, fixture.worker
                for service in (server.states, server.goals, server.registry, server.terminals, server.skills):
                    self.assertIs(service.clock, fixture.clock)
                self.assertIsNone(server.brain)
                self.assertIsNone(server.decisions)
                peer = ScriptedForgePeer(fixture)
                response, _ = peer.post("/v2/execution/open", {"version": 2, "session": "absent"})
                self.assertEqual(response["status"], 409)
                fixture.advance(1001)
                self.assertEqual(fixture.clock(), 1.001)
                raise RuntimeError("intentional")
        self.assertIsNone(fixture.server)
        self.assertFalse(worker.is_alive())
        self.assertTrue(all(not t.is_alive() for t in server.handlers))

    def test_only_five_operations_and_clock_restart_control(self):
        self.assertEqual(set(OPS), {"http", "receipt", "advance", "tick", "restart"})
        runner = ScenarioRunner()
        runner.run({"scenarioVersion": 1, "name": "fixture-control", "steps": [
            {"op": "advance", "ms": 1001}, {"op": "tick"}, {"op": "restart"},
        ]})
        self.assertEqual([r["virtualMsAfter"] for r in runner.transcript], [1001, 1001, 0])
        with self.assertRaises(ScenarioFormatError):
            runner.run({"scenarioVersion": 1, "name": "invalid", "steps": [{"op": "sleep"}]})

    def test_wrong_expectation_fails_with_wire_diagnostics(self):
        document = json.loads(C1.read_text(encoding="utf-8"))
        document["steps"] = document["steps"][:1]
        document["steps"][0]["expect"]["json"]["/sequence"] = 99
        runner = ScenarioRunner()
        with self.assertRaisesRegex(AssertionError, r"step 1.*expected 99, got 0"):
            runner.run(document)
        summary = json.loads((runner.artifact_dir / "summary.json").read_text(encoding="utf-8"))
        self.assertEqual(summary["status"], "failed")
        row = json.loads((runner.artifact_dir / "transcript.jsonl").read_text(encoding="utf-8"))
        self.assertEqual(row["path"], "/v1/snapshot")
        self.assertEqual(row["request"]["state"]["companion"]["inventory"], {"minecraft:log": 3})
        self.assertEqual(row["response"]["body"]["sequence"], 0)

    def test_reference_and_oracle_preserve_wire_types(self):
        runner = ScenarioRunner()
        runner.captures = {"old": {"body": {"count": 1, "nested": [True, None]}}}
        result = runner.resolve({"$ref": "old/body/nested"})
        result.append("changed")
        self.assertEqual(runner.captures["old"]["body"]["nested"], [True, None])
        self.assertFalse(equal({"count": True}, {"count": 1}))
        self.assertFalse(equal(1, 1.0))
        self.assertIsNone(pointer({"a/b": {"~": None}}, "/a~1b/~0"))
        with self.assertRaises(KeyError):
            pointer({"present": None}, "/missing")
        with self.assertRaises(ScenarioFormatError):
            runner.resolve({"$ref": "missing/body"})

    def test_receipt_uses_issuing_descriptor_and_does_not_mutate_it(self):
        view = {"session": "old-session", "daemonEpoch": "old-epoch", "goalRevision": 7,
                "action": {"type": "mine_target", "skillInstanceId": "old-skill", "actionId": "a",
                           "actionSequence": 2, "block": "minecraft:log"}}
        original = copy.deepcopy(view)
        receipt = ScriptedForgePeer.receipt(view, "succeeded", "completed", 1)
        self.assertEqual(receipt["goalRevision"], 7)
        self.assertEqual(receipt["skillInstanceId"], "old-skill")
        self.assertEqual(receipt["destroyed"], {"block": "minecraft:log", "count": 1})
        self.assertNotIn("acquired", receipt)
        receipt["destroyed"]["count"] = 5
        self.assertEqual(view, original)


if __name__ == "__main__":
    unittest.main()
