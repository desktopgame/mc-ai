import copy
import json
import sys
import threading
import unittest
from pathlib import Path
from contextlib import closing
from http.client import HTTPConnection
from http.server import ThreadingHTTPServer

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))
from state_cache import StateCache, SyncError
from daemon import Handler
from decision import DecisionService, MockDecisionProvider

FIXTURES = Path(__file__).resolve().parents[2] / "protocol" / "examples"
def fixture(name): return json.loads((FIXTURES / name).read_text())
def snapshot(): return fixture("snapshot-request.json")
def events(): return fixture("events-request.json")


class StateCacheTests(unittest.TestCase):
    def test_snapshot_delta_and_read_copy(self):
        cache = StateCache()
        cache.update(snapshot(), True)
        cache.update(events())
        view = cache.view({"version": 1})
        self.assertEqual(view["sequence"], 1)
        self.assertEqual(view["state"]["owner"]["position"], [12, 64, 0])
        self.assertEqual(view["state"]["owner"]["inventory"], {"minecraft:stone": 3, "minecraft:torch": 3})
        self.assertEqual(view["state"]["companion"]["health"], 18)
        self.assertEqual(len(view["events"]), 5)
        view["state"]["owner"]["position"][0] = 999
        self.assertEqual(cache.view({"version": 1})["state"]["owner"]["position"][0], 12)

    def test_out_of_order_duplicate_and_restart_require_snapshot(self):
        cache = StateCache()
        with self.assertRaises(SyncError): cache.update(events())
        cache.update(snapshot(), True)
        wrong = events(); wrong["sequence"] = 2
        with self.assertRaises(SyncError): cache.update(wrong)
        cache.update(events())
        with self.assertRaises(SyncError): cache.update(events())
        with self.assertRaises(SyncError): cache.update(snapshot(), True)
        fresh = snapshot(); fresh["session"] = "reconnected"
        cache.update(fresh, True)
        self.assertEqual(cache.view({"version": 1})["session"], "reconnected")

    def test_invalid_batch_is_atomic(self):
        cache = StateCache(); cache.update(snapshot(), True)
        for bad_event in [{"type": "shell", "command": "secret"},
                          {"type": "health_changed", "entity": "owner", "health": float("nan")},
                          {"type": "inventory_changed", "added": {}, "removed": {"minecraft:stone": 99}}]:
            batch = events(); batch["events"].append(bad_event)
            with self.assertRaises((ValueError, SyncError)): cache.update(batch)
            self.assertEqual(cache.view({"version": 1})["state"], snapshot()["state"])
            self.assertEqual(cache.view({"version": 1})["sequence"], 0)

    def test_hostiles_task_events_and_missing_companion(self):
        cache = StateCache(); cache.update(snapshot(), True); cache.update(events())
        delta = {"version": 1, "session": snapshot()["session"], "sequence": 2, "events": [
            {"type": "hostile_updated", "id": "mob-10", "observation": {"type": "Skeleton", "distance": 4}},
            {"type": "task_failed", "task": "follow", "result": "path_not_found"}]}
        cache.update(delta)
        self.assertEqual(cache.view({"version": 1})["state"]["hostiles"]["mob-10"]["distance"], 4)
        delta["sequence"] = 3; delta["events"] = [{"type": "hostile_left_range", "id": "mob-10"},
                                                 {"type": "task_completed", "task": "idle", "result": "look_completed"}]
        cache.update(delta)
        self.assertEqual(cache.view({"version": 1})["state"]["hostiles"], {})
        fresh = snapshot(); fresh["sequence"] = 4; fresh["state"]["companion"] = None
        cache.update(fresh, True)
        batch = events(); batch["sequence"] = 5
        with self.assertRaises(ValueError): cache.update(batch)

    def test_stale_heartbeat_history_bounds_and_session_isolation(self):
        now = [0.0]; cache = StateCache(clock=lambda: now[0]); cache.update(snapshot(), True)
        now[0] = 16
        self.assertTrue(cache.view({"version": 1})["stale"])
        for seq in range(1, 110):
            cache.update({"version": 1, "session": snapshot()["session"], "sequence": seq,
                          "events": [{"type": "health_changed", "entity": "owner", "health": 20}]})
        self.assertFalse(cache.view({"version": 1})["stale"])
        self.assertEqual(len(cache.view({"version": 1})["events"]), 100)
        for i in range(35):
            data = snapshot(); data["session"] = "world-" + str(i); cache.update(data, True)
        self.assertEqual(len(cache.entries), 32)
        with self.assertRaises(SyncError): cache.view({"version": 1, "session": snapshot()["session"]})

    def test_private_fields_rejected(self):
        data = snapshot(); data["state"]["owner"]["name"] = "PRIVATE_TEST_MARKER_12345"
        with self.assertRaises(ValueError): StateCache().update(data, True)


class StateHTTPTests(unittest.TestCase):
    def test_snapshot_events_cached_decision_and_stale_rejection(self):
        now = [0.0]
        server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        server.states = StateCache(clock=lambda: now[0])
        server.decisions = DecisionService(MockDecisionProvider())
        thread = threading.Thread(target=server.serve_forever, daemon=True); thread.start()
        def post(path, data):
            with closing(HTTPConnection(*server.server_address, timeout=2)) as conn:
                conn.request("POST", path, json.dumps(data), {"Content-Type": "application/json"})
                response = conn.getresponse(); return response.status, json.loads(response.read())
        try:
            self.assertEqual(post("/v1/events", events())[0], 409)
            self.assertEqual(post("/v1/snapshot", snapshot())[0], 200)
            self.assertEqual(post("/v1/events", events())[0], 200)
            self.assertEqual(post("/v1/state", {"version": 1})[1]["sequence"], 1)
            request = {"version": 1, "session": snapshot()["session"], "goal": {"type": "follow_owner"}, "availableActions": ["follow", "stop"]}
            status, result = post("/v1/decision", request)
            self.assertEqual(status, 200); self.assertEqual(result["decision"]["action"], "follow")
            self.assertFalse(result["executed"])
            now[0] = 20
            self.assertEqual(post("/v1/decision", request)[0], 409)
        finally:
            server.shutdown(); server.server_close(); thread.join()
