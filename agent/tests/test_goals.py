import copy
import json
import sys
import threading
import time
import unittest
from contextlib import closing
from http.client import HTTPConnection
from http.server import ThreadingHTTPServer
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))
from goals import GoalManager
from decision import DecisionService, MockDecisionProvider
from state_cache import StateCache, SyncError
from daemon import Handler


def snapshot():
    return {"version": 1, "session": "world", "sequence": 0,
            "state": {"dimension": 0, "owner": {"position": [8, 64, 0], "health": 20, "inventory": {}},
                      "companion": {"id": "companion", "position": [0, 64, 0], "health": 20,
                                    "task": "idle", "result": "none", "inventory": {}},
                      "hostiles": {}, "items": {}}}


def request(revision=1, goal="follow_owner", session="world"):
    return {"version": 1, "session": session, "goalRevision": revision, "goal": goal}


def wait_for(manager, payload, expected="ready"):
    until = time.monotonic() + 3
    while time.monotonic() < until:
        value = manager.update(payload)
        if value["status"] == expected: return value
        time.sleep(.005)
    raise AssertionError("goal did not become " + expected + ": " + str(value))


class DelayedProvider(MockDecisionProvider):
    def __init__(self):
        self.started = threading.Event()
        self.release = threading.Event()
        self.inputs = []

    def decide(self, payload):
        self.inputs.append(copy.deepcopy(payload))
        if len(self.inputs) == 1:
            self.started.set()
            if not self.release.wait(3): raise AssertionError("test failed to release provider")
        return super().decide(payload)


class GoalTests(unittest.TestCase):
    def test_nearby_follow_remains_running_until_cancelled(self):
        states, manager = self.create()
        data = snapshot(); data["sequence"] = 1; data["state"]["owner"]["position"] = [1, 64, 0]
        states.update(data, True)
        ready = wait_for(manager, request())
        self.assertEqual(ready["action"]["decision"]["action"], "follow")
        self.report(manager, ready, "running")
        data["sequence"] = 2; data["state"]["owner"]["position"] = [8, 64, 0]
        data["state"]["companion"]["task"] = "follow"
        data["state"]["companion"]["result"] = "path_retrying"
        states.update(data, True)
        self.assertEqual(manager.update(request())["status"], "running")
        self.assertEqual(manager.update(request(2, None))["status"], "idle")

    def create(self, provider=None, clock=time.monotonic):
        states = StateCache(clock=clock); states.update(snapshot(), True)
        manager = GoalManager(states, DecisionService(provider or MockDecisionProvider()), clock=clock)
        self.addCleanup(self.join, manager)
        return states, manager

    def join(self, manager):
        worker = manager.worker
        if worker:
            worker.join(4)
            self.assertFalse(worker.is_alive())

    def report(self, manager, reply, status, reason="accepted"):
        return manager.result({"version": 1, "session": reply["session"], "goalRevision": reply["goalRevision"],
                               "actionId": reply["action"]["actionId"], "status": status, "reason": reason})

    def test_stop_during_inference_does_not_wait_or_resurrect(self):
        provider = DelayedProvider(); _, manager = self.create(provider)
        manager.update(request()); self.assertTrue(provider.started.wait(1))
        self.assertEqual(manager.update(request(2, None))["status"], "idle")
        # Provider is still blocked: cancellation has no dependency on its completion.
        self.assertFalse(provider.release.is_set())
        provider.release.set(); self.join(manager)
        self.assertIsNone(manager.update(request(2, None))["action"])
        with self.assertRaises(SyncError): manager.update(request())

    def test_latest_pending_wins_and_stale_result_cannot_overwrite(self):
        provider = DelayedProvider(); _, manager = self.create(provider)
        manager.update(request()); self.assertTrue(provider.started.wait(1))
        manager.update(request(2, "stop")); manager.update(request(3, "look_at_owner"))
        provider.release.set()
        reply = wait_for(manager, request(3, "look_at_owner"))
        self.assertEqual(reply["action"]["decision"]["action"], "look")
        self.assertEqual([p["goal"]["type"] for p in provider.inputs], ["follow_owner", "look_at_owner"])
        self.report(manager, reply, "running")
        manager.update(request(4, "follow_owner"))
        self.report(manager, reply, "succeeded", "completed")
        self.assertEqual(wait_for(manager, request(4))["goalRevision"], 4)

    def test_duplicate_poll_and_reports_do_not_repeat_action(self):
        _, manager = self.create(); reply = wait_for(manager, request())
        self.assertEqual(reply, manager.update(request()))
        self.report(manager, reply, "running"); self.report(manager, reply, "running")
        self.assertEqual(manager.update(request())["status"], "running")
        self.assertIsNone(manager.update(request())["action"])
        self.report(manager, reply, "succeeded", "completed")
        self.report(manager, reply, "running")
        self.assertEqual(manager.update(request())["status"], "succeeded")
        with self.assertRaises(SyncError): manager.update(request(1, "look_at_owner"))

    def test_state_rechecked_after_inference_and_before_delivery(self):
        provider = DelayedProvider(); states, manager = self.create(provider)
        manager.update(request()); self.assertTrue(provider.started.wait(1))
        data = snapshot(); data["sequence"] = 1; data["state"]["companion"]["health"] = 1
        states.update(data, True); provider.release.set()
        self.assertIsNone(wait_for(manager, request(), "failed")["action"])
        data["sequence"] = 2; data["state"]["companion"]["health"] = 20
        states.update(data, True)
        wait_for(manager, request(2))
        data["sequence"] = 3; data["state"]["companion"]["id"] = "replacement"
        states.update(data, True)
        self.assertEqual(manager.update(request(2))["status"], "failed")

    def test_pickup_goal_uses_observed_items_and_reports_its_own_failures(self):
        states, manager = self.create()
        data = snapshot(); data["sequence"] = 1
        data["state"]["items"] = {"item-3": {"type": "minecraft:diamond", "distance": 4}}
        states.update(data, True)
        ready = wait_for(manager, request(1, "pickup_item"))
        self.assertEqual(ready["action"]["decision"], {"action": "pickup"})
        self.assertEqual(ready["action"]["reasonCode"], "goal_pickup")
        self.report(manager, ready, "running")
        self.report(manager, ready, "failed", "inventory_full")
        self.assertEqual(manager.update(request(1, "pickup_item"))["status"], "failed")
        # The item disappeared before the next goal: stop is the only validated outcome.
        data["sequence"] = 2; data["state"]["items"] = {}
        states.update(data, True)
        empty = wait_for(manager, request(2, "pickup_item"))
        self.assertEqual(empty["action"]["decision"], {"action": "stop"})
        self.assertEqual(empty["action"]["reasonCode"], "no_item_in_range")

    def test_pickup_input_carries_only_counts_and_distance(self):
        provider = DelayedProvider(); states, manager = self.create(provider)
        data = snapshot(); data["sequence"] = 1
        data["state"]["items"] = {"item-3": {"type": "minecraft:diamond", "distance": 6},
                                  "item-4": {"type": "minecraft:dirt", "distance": 2}}
        data["state"]["companion"]["inventory"] = {"minecraft:diamond": 1}
        states.update(data, True)
        manager.update(request(1, "pickup_item"))
        self.assertTrue(provider.started.wait(1))
        provider.release.set(); self.join(manager)
        sent = json.dumps(provider.inputs[0])
        self.assertEqual(provider.inputs[0]["state"]["items"], {"count": 2, "nearestDistance": 2})
        self.assertNotIn("item-", sent)
        self.assertNotIn("minecraft", sent)

    def test_deposit_goal_uses_the_carried_count_from_observations(self):
        states, manager = self.create()
        data = snapshot(); data["sequence"] = 1
        data["state"]["companion"]["inventory"] = {"minecraft:sand": 2, "minecraft:dirt": 1}
        states.update(data, True)
        ready = wait_for(manager, request(1, "deposit_items"))
        self.assertEqual(ready["action"]["decision"], {"action": "deposit"})
        self.assertEqual(ready["action"]["reasonCode"], "goal_deposit")
        self.report(manager, ready, "running")
        self.report(manager, ready, "failed", "owner_inventory_full")
        self.assertEqual(manager.update(request(1, "deposit_items"))["status"], "failed")
        # Everything was handed over already: deposit must not be chosen again.
        data["sequence"] = 2; data["state"]["companion"]["inventory"] = {}
        states.update(data, True)
        empty = wait_for(manager, request(2, "deposit_items"))
        self.assertEqual(empty["action"]["decision"], {"action": "stop"})
        self.assertEqual(empty["action"]["reasonCode"], "inventory_empty")

    def test_deposit_input_carries_only_a_count(self):
        provider = DelayedProvider(); states, manager = self.create(provider)
        data = snapshot(); data["sequence"] = 1
        data["state"]["companion"]["inventory"] = {"minecraft:sand": 2, "minecraft:dirt": 1}
        states.update(data, True)
        manager.update(request(1, "deposit_items"))
        self.assertTrue(provider.started.wait(1))
        provider.release.set(); self.join(manager)
        self.assertEqual(provider.inputs[0]["state"]["companion"]["carrying"], 3)
        self.assertNotIn("minecraft", json.dumps(provider.inputs[0]))

    def test_expiry_and_cancellation_without_fresh_observations(self):
        now = [0.0]; _, manager = self.create(clock=lambda: now[0])
        wait_for(manager, request()); now[0] = 11
        self.assertEqual(manager.update(request())["status"], "failed")
        now[0] = 100
        self.assertEqual(manager.update(request(2, None))["status"], "idle")
        with self.assertRaises(SyncError): manager.update(request(3))

    def test_boundaries_bounded_sessions_and_no_social_context(self):
        provider = DelayedProvider(); _, manager = self.create(provider)
        bad = request(); bad["private_memory"] = "PRIVATE_TEST_MARKER_12345"
        with self.assertRaises(ValueError): manager.update(bad)
        manager.update(request()); self.assertTrue(provider.started.wait(1))
        provider.release.set(); wait_for(manager, request())
        serialized = json.dumps(provider.inputs)
        for value in ("PRIVATE_TEST_MARKER_12345", "world", "actionId", "goalRevision"):
            self.assertNotIn(value, serialized)
        for i in range(40): manager.update(request(0, None, "new-" + str(i)))
        self.assertLessEqual(len(manager.entries), 32)
        self.assertLessEqual(len(manager.pending), 32)

    def test_session_change_discards_inflight_action_locally_but_cannot_affect_new_goal(self):
        provider = DelayedProvider(); states, manager = self.create(provider)
        manager.update(request()); self.assertTrue(provider.started.wait(1))
        fresh = snapshot(); fresh["session"] = "new-world"; states.update(fresh, True)
        manager.update(request(1, "look_at_owner", "new-world"))
        manager.update(request(2, None, "world")); provider.release.set()
        reply = wait_for(manager, request(1, "look_at_owner", "new-world"))
        self.assertEqual(reply["session"], "new-world")
        self.assertIsNone(manager.update(request(2, None, "world"))["action"])

    def test_http_lifecycle(self):
        states, manager = self.create()
        server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        server.states = states; server.goals = manager
        worker = threading.Thread(target=server.serve_forever, daemon=True); worker.start()
        def post(path, data):
            with closing(HTTPConnection(*server.server_address, timeout=2)) as conn:
                conn.request("POST", path, json.dumps(data), {"Content-Type": "application/json"})
                response = conn.getresponse(); return response.status, json.loads(response.read())
        try:
            self.assertEqual(post("/v1/goal", request())[0], 200)
            ready = wait_for(manager, request())
            body = {"version": 1, "session": "world", "goalRevision": 1, "actionId": ready["action"]["actionId"], "status": "running", "reason": "accepted"}
            self.assertEqual(post("/v1/action-result", body), (200, {"version": 1, "accepted": True}))
            self.assertEqual(post("/v1/goal", request(2, None))[1]["status"], "idle")
            self.assertEqual(post("/v1/goal", request())[0], 409)
            body["actionId"] = "unknown"
            self.assertEqual(post("/v1/action-result", body)[0], 409)
        finally:
            server.shutdown(); server.server_close(); worker.join()
