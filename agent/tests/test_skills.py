import json
import sys
import threading
import unittest
from contextlib import closing
from http.client import HTTPConnection
from http.server import ThreadingHTTPServer
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))
from skills import SkillManager, SEARCH_WINDOW
from execution_registry import ExecutionRegistry
from state_cache import StateCache, SyncError
from skill_protocol import SkillRequestError, SkillSyncError
from daemon import Handler


def snapshot(items=None, inventory=None, seq=0, session="world", companion="companion"):
    return {"version": 1, "session": session, "sequence": seq,
            "state": {"dimension": 0,
                      "owner": {"position": [0, 64, 0], "health": 20, "inventory": {}},
                      "companion": {"id": companion, "position": [0, 64, 0], "health": 20,
                                    "task": "idle", "result": "none", "inventory": inventory or {}},
                      "hostiles": {}, "items": items or {}}}


def drop(distance=4, kind="minecraft:log"):
    return {"type": kind, "distance": distance}


def goal(revision, item="minecraft:log", count=5, session="world", epoch=None, goal_type="collect_drop"):
    if goal_type == "null":
        body = None
    else:
        body = {"type": "collect_drop", "target": {"item": item}, "count": count, "constraints": []}
    return {"version": 2, "session": session, "daemonEpoch": epoch, "goalRevision": revision, "goal": body}


class SkillTests(unittest.TestCase):
    def create(self, clock=None):
        self.now = [0.0]
        clock = clock or (lambda: self.now[0])
        states = StateCache(clock=clock)
        states.update(snapshot(seq=0), True)
        registry = ExecutionRegistry(clock=clock)
        manager = SkillManager(states, registry, clock=clock)
        opened = manager.open({"version": 2, "session": "world"})
        return states, manager, opened["daemonEpoch"]

    def result(self, manager, action, status, reason, count=0, item="minecraft:log"):
        return manager.result({"version": 2, "session": "world", "daemonEpoch": self.epoch,
                               "goalRevision": action["skillRevision"] if "skillRevision" in action else self.revision,
                               "skillInstanceId": action["skillInstanceId"], "actionId": action["actionId"],
                               "actionSequence": action["actionSequence"], "status": status, "reason": reason,
                               "acquired": {"item": item, "count": count}})

    def accept(self, manager, revision, count=5, item="minecraft:log"):
        self.revision = revision
        view = manager.update(goal(revision, item=item, count=count, epoch=self.epoch))
        return view

    def test_happy_path_counts_only_skill_actions(self):
        states, manager, self.epoch = self.create()
        states.update(snapshot(items={"item-a": drop(4)}, inventory={"minecraft:log": 3}, seq=1), True)
        view = self.accept(manager, 1, count=5)
        self.assertEqual(view["status"], "running")
        self.assertEqual(view["action"]["maxCount"], 5)
        self.assertEqual(view["action"]["targetRef"], "item-a")
        self.assertEqual(view["action"]["item"], "minecraft:log")
        first = view["action"]
        manager.result({"version": 2, "session": "world", "daemonEpoch": self.epoch, "goalRevision": 1,
                        "skillInstanceId": first["skillInstanceId"], "actionId": first["actionId"],
                        "actionSequence": first["actionSequence"], "status": "running", "reason": "accepted",
                        "acquired": {"item": "minecraft:log", "count": 0}})
        states.update(snapshot(items={"item-a": drop(4)}, inventory={"minecraft:log": 3}, seq=2), True)
        manager.result({"version": 2, "session": "world", "daemonEpoch": self.epoch, "goalRevision": 1,
                        "skillInstanceId": first["skillInstanceId"], "actionId": first["actionId"],
                        "actionSequence": first["actionSequence"], "status": "succeeded", "reason": "completed",
                        "acquired": {"item": "minecraft:log", "count": 2}})
        view = manager.update(goal(1, epoch=self.epoch))
        second = view["action"]
        self.assertIsNotNone(second)
        self.assertEqual(second["maxCount"], 3)
        manager.result({"version": 2, "session": "world", "daemonEpoch": self.epoch, "goalRevision": 1,
                        "skillInstanceId": second["skillInstanceId"], "actionId": second["actionId"],
                        "actionSequence": second["actionSequence"], "status": "running", "reason": "accepted",
                        "acquired": {"item": "minecraft:log", "count": 0}})
        states.update(snapshot(items={}, inventory={"minecraft:log": 3}, seq=3), True)
        manager.result({"version": 2, "session": "world", "daemonEpoch": self.epoch, "goalRevision": 1,
                        "skillInstanceId": second["skillInstanceId"], "actionId": second["actionId"],
                        "actionSequence": second["actionSequence"], "status": "succeeded", "reason": "completed",
                        "acquired": {"item": "minecraft:log", "count": 3}})
        view = manager.update(goal(1, epoch=self.epoch))
        self.assertEqual(view["status"], "completed")
        self.assertEqual(view["skill"]["result"]["reason"], "completed")
        self.assertEqual(view["skill"]["result"]["progress"], {"requested": 5, "acquired": 5, "complete": True})
        self.assertIsNone(view["action"])

    def test_duplicate_and_conflicting_results(self):
        states, manager, self.epoch = self.create()
        states.update(snapshot(items={"item-a": drop(4)}, seq=1), True)
        view = self.accept(manager, 1, count=2)
        action = view["action"]
        body = {"version": 2, "session": "world", "daemonEpoch": self.epoch, "goalRevision": 1,
                "skillInstanceId": action["skillInstanceId"], "actionId": action["actionId"],
                "actionSequence": action["actionSequence"], "status": "succeeded", "reason": "completed",
                "acquired": {"item": "minecraft:log", "count": 2}}
        self.assertEqual(manager.result(body), {"version": 2, "accepted": True})
        self.assertEqual(manager.result(body), {"version": 2, "accepted": True})
        conflict = dict(body, acquired={"item": "minecraft:log", "count": 1})
        with self.assertRaises(SkillSyncError):
            manager.result(conflict)
        late = dict(body, status="running", reason="accepted", acquired={"item": "minecraft:log", "count": 0})
        with self.assertRaises(SkillSyncError):
            manager.result(late)
        unknown = dict(body, actionId="not-issued", actionSequence=99)
        with self.assertRaises(SkillSyncError):
            manager.result(unknown)
        self.assertEqual(manager.update(goal(1, count=2, epoch=self.epoch))["status"], "completed")

    def test_failed_action_retries_then_exhausts(self):
        states, manager, self.epoch = self.create()
        states.update(snapshot(items={"item-a": drop(4), "item-b": drop(6), "item-c": drop(8),
                                      "item-d": drop(10)}, seq=1), True)
        action = self.accept(manager, 1, count=3)["action"]
        for _ in range(3):
            body = {"version": 2, "session": "world", "daemonEpoch": self.epoch, "goalRevision": 1,
                    "skillInstanceId": action["skillInstanceId"], "actionId": action["actionId"],
                    "actionSequence": action["actionSequence"], "status": "failed", "reason": "target_lost",
                    "acquired": {"item": "minecraft:log", "count": 0}}
            manager.result(body)
            action = manager.update(goal(1, count=3, epoch=self.epoch))["action"]
        reason = manager.update(goal(1, count=3, epoch=self.epoch))["skill"]["result"]["reason"]
        self.assertEqual(reason, "retry_exhausted")

    def test_no_item_in_range_after_search_window(self):
        states, manager, self.epoch = self.create()
        view = self.accept(manager, 1, count=2)
        self.assertIsNone(view["action"])
        self.assertEqual(view["status"], "running")
        self.now[0] = SEARCH_WINDOW + 1
        manager.tick()
        view = manager.update(goal(1, count=2, epoch=self.epoch))
        self.assertEqual(view["status"], "failed")
        self.assertEqual(view["skill"]["result"]["reason"], "no_item_in_range")
        self.assertEqual(view["skill"]["result"]["progress"]["complete"], True)

    def test_stale_cache_reports_stale_state(self):
        states, manager, self.epoch = self.create()
        self.accept(manager, 1, count=2)
        self.now[0] = 30
        manager.tick()
        self.assertEqual(manager.update(goal(1, count=2, epoch=self.epoch))["skill"]["result"]["reason"], "stale_state")

    def test_cancelled_receipt_without_prior_cancel_is_terminal(self):
        states, manager, self.epoch = self.create()
        states.update(snapshot(items={"item-a": drop(4), "item-b": drop(6)}, seq=1), True)
        action = self.accept(manager, 1, count=3)["action"]
        body = {"version": 2, "session": "world", "daemonEpoch": self.epoch, "goalRevision": 1,
                "skillInstanceId": action["skillInstanceId"], "actionId": action["actionId"],
                "actionSequence": action["actionSequence"], "status": "cancelled", "reason": "replaced",
                "acquired": {"item": "minecraft:log", "count": 0}}
        manager.result(body)
        view = manager.update(goal(1, count=3, epoch=self.epoch))
        self.assertEqual(view["status"], "cancelled")
        self.assertIsNone(view["action"])
        self.assertEqual(view["skill"]["result"]["reason"], "replaced")

    def test_cancellation_with_inflight_action_settles(self):
        states, manager, self.epoch = self.create()
        states.update(snapshot(items={"item-a": drop(4)}, seq=1), True)
        action = self.accept(manager, 1, count=3)["action"]
        cancelled = manager.update(goal(2, epoch=self.epoch, goal_type="null"))
        self.assertEqual(cancelled["status"], "idle")
        # The old skill is settling; a cancelled receipt with count 0 closes it.
        body = {"version": 2, "session": "world", "daemonEpoch": self.epoch, "goalRevision": 1,
                "skillInstanceId": action["skillInstanceId"], "actionId": action["actionId"],
                "actionSequence": action["actionSequence"], "status": "cancelled", "reason": "replaced",
                "acquired": {"item": "minecraft:log", "count": 0}}
        manager.result(body)
        status = manager.status({"version": 2, "session": "world", "daemonEpoch": self.epoch,
                                 "skillInstanceId": action["skillInstanceId"]})
        self.assertEqual(status["skill"]["result"]["status"], "cancelled")
        self.assertEqual(status["skill"]["result"]["progress"], {"requested": 3, "acquired": 0, "complete": True})

    def test_revision_and_epoch_guards(self):
        states, manager, self.epoch = self.create()
        self.accept(manager, 2, count=2)
        with self.assertRaises(SkillSyncError):
            manager.update(goal(1, epoch=self.epoch))
        with self.assertRaises(SkillSyncError):
            manager.update(goal(2, item="minecraft:cobblestone", epoch=self.epoch))
        with self.assertRaises(SkillSyncError):
            manager.update(goal(3, epoch="deadbeef"))
        with self.assertRaises(SkillSyncError):
            manager.update({"version": 2, "session": "unopened", "daemonEpoch": self.epoch,
                            "goalRevision": 1, "goal": None})

    def test_protocol_rejects_invalid_requests(self):
        _, manager, self.epoch = self.create()
        for bad in (-1, 0, 65, True, 4.5, "5"):
            with self.assertRaises(SkillRequestError):
                manager.update(goal(1, count=bad, epoch=self.epoch))
        for item in ("minecraft:diamond", "", "nope"):
            with self.assertRaises(SkillRequestError):
                manager.update(goal(1, item=item, epoch=self.epoch))
        with self.assertRaises(SkillRequestError):
            manager.update({"version": 2, "session": "world", "daemonEpoch": self.epoch, "goalRevision": 1,
                            "goal": {"type": "collect_drop", "target": {"item": "minecraft:log"},
                                     "count": 1, "constraints": [{"type": "avoid_entity"}]}})

    def test_http_v2_lifecycle(self):
        states, manager, self.epoch = self.create()
        states.update(snapshot(items={"item-a": drop(4)}, seq=1), True)
        server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        server.skills = manager
        worker = threading.Thread(target=server.serve_forever, daemon=True)
        worker.start()

        def post(path, data):
            with closing(HTTPConnection(*server.server_address, timeout=2)) as conn:
                conn.request("POST", path, json.dumps(data), {"Content-Type": "application/json"})
                response = conn.getresponse()
                return response.status, json.loads(response.read())

        try:
            status, opened = post("/v2/execution/open", {"version": 2, "session": "world"})
            self.assertEqual(status, 200)
            self.assertEqual(opened["capabilities"], ["collect_drop_v1"])
            status, view = post("/v2/goal", goal(1, count=2, epoch=opened["daemonEpoch"]))
            self.assertEqual(status, 200)
            self.assertEqual(view["action"]["type"], "pickup_target")
            action = view["action"]
            result = {"version": 2, "session": "world", "daemonEpoch": opened["daemonEpoch"], "goalRevision": 1,
                      "skillInstanceId": action["skillInstanceId"], "actionId": action["actionId"],
                      "actionSequence": action["actionSequence"], "status": "succeeded", "reason": "completed",
                      "acquired": {"item": "minecraft:log", "count": 2}}
            self.assertEqual(post("/v2/action-result", result), (200, {"version": 2, "accepted": True}))
            status, view = post("/v2/skill-status", {"version": 2, "session": "world",
                                                     "daemonEpoch": opened["daemonEpoch"],
                                                     "skillInstanceId": action["skillInstanceId"]})
            self.assertEqual(view["status"], "completed")
            self.assertIsNone(view["action"])
            self.assertEqual(post("/v2/goal", goal(3, count=99, epoch=opened["daemonEpoch"]))[0], 400)
            self.assertEqual(post("/v2/goal", goal(1, epoch="stale-epoch"))[0], 409)
        finally:
            server.shutdown()
            server.server_close()
            worker.join()


if __name__ == "__main__":
    unittest.main()
