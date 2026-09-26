import json
import sys
import threading
import unittest
from contextlib import closing
from http.client import HTTPConnection
from http.server import ThreadingHTTPServer
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))
from skills import SkillManager, SEARCH_WINDOW, SKILL_DEADLINE, CANCEL_GRACE, MAX_TERMINAL
from execution_registry import ExecutionRegistry
from state_cache import StateCache, SyncError
from skill_protocol import SkillRequestError, SkillSyncError
from daemon import Handler


def snapshot(items=None, inventory=None, seq=0, session="world", companion="companion", blocks=None):
    return {"version": 1, "session": session, "sequence": seq,
            "state": {"dimension": 0,
                      "owner": {"position": [0, 64, 0], "health": 20, "inventory": {}},
                      "companion": {"id": companion, "position": [0, 64, 0], "health": 20,
                                    "task": "idle", "result": "none", "inventory": inventory or {}},
                      "hostiles": {}, "items": items or {}, "blocks": blocks or {}}}


def drop(distance=4, kind="minecraft:log"):
    return {"type": kind, "distance": distance}


def block(distance=4, kind="minecraft:log"):
    return {"type": kind, "distance": distance}


def mine_goal(revision, block_name="minecraft:log", count=1, session="world", epoch=None):
    return {"version": 2, "session": session, "daemonEpoch": epoch, "goalRevision": revision,
            "goal": {"type": "mine", "target": {"block": block_name}, "count": count, "constraints": []}}


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

    def receipt(self, action, status, reason, payload, revision=1):
        body = {"version": 2, "session": "world", "daemonEpoch": self.epoch, "goalRevision": revision,
                "skillInstanceId": action["skillInstanceId"], "actionId": action["actionId"],
                "actionSequence": action["actionSequence"], "status": status, "reason": reason}
        body.update(payload)
        return body

    def test_mine_happy_path_destroys_one_block(self):
        states, manager, self.epoch = self.create()
        states.update(snapshot(blocks={"block-0_64_0": block(4)}, seq=1), True)
        view = manager.update(mine_goal(1, epoch=self.epoch))
        action = view["action"]
        self.assertEqual(action["type"], "mine_target")
        self.assertEqual(action["block"], "minecraft:log")
        self.assertEqual(action["targetRef"], "block-0_64_0")
        self.assertNotIn("maxCount", action)
        self.assertNotIn("item", action)
        manager.result(self.receipt(action, "running", "accepted", {"destroyed": {"block": "minecraft:log", "count": 0}}))
        states.update(snapshot(blocks={}, seq=2), True)
        manager.result(self.receipt(action, "succeeded", "completed", {"destroyed": {"block": "minecraft:log", "count": 1}}))
        view = manager.update(mine_goal(1, epoch=self.epoch))
        self.assertEqual(view["status"], "completed")
        self.assertEqual(view["skill"]["result"]["progress"], {"requested": 1, "mined": 1, "complete": True})

    def test_mine_tool_unavailable_is_terminal(self):
        states, manager, self.epoch = self.create()
        states.update(snapshot(blocks={"block-0_64_0": block(4)}, seq=1), True)
        action = manager.update(mine_goal(1, epoch=self.epoch))["action"]
        manager.result(self.receipt(action, "failed", "tool_unavailable", {"destroyed": {"block": "minecraft:log", "count": 0}}))
        view = manager.update(mine_goal(1, epoch=self.epoch))
        self.assertEqual(view["status"], "failed")
        self.assertEqual(view["skill"]["result"]["reason"], "tool_unavailable")
        self.assertIsNone(view["action"])
        self.assertEqual(view["skill"]["result"]["progress"]["complete"], True)

    def test_mine_rejects_item_receipt_payload(self):
        states, manager, self.epoch = self.create()
        states.update(snapshot(blocks={"block-0_64_0": block(4)}, seq=1), True)
        action = manager.update(mine_goal(1, epoch=self.epoch))["action"]
        with self.assertRaises(SkillSyncError):
            manager.result(self.receipt(action, "succeeded", "completed", {"acquired": {"item": "minecraft:log", "count": 1}}))

    def test_mine_no_block_in_range(self):
        states, manager, self.epoch = self.create()
        view = manager.update(mine_goal(1, epoch=self.epoch))
        self.assertIsNone(view["action"])
        self.now[0] = SEARCH_WINDOW + 1
        manager.tick()
        view = manager.update(mine_goal(1, epoch=self.epoch))
        self.assertEqual(view["status"], "failed")
        self.assertEqual(view["skill"]["result"]["reason"], "no_block_in_range")

    def test_mine_blocked_target_is_skipped_never_reissued_or_auto_broken(self):
        states, manager, self.epoch = self.create()
        states.update(snapshot(blocks={"block-a": block(4), "block-b": block(6)}, seq=1), True)
        action = manager.update(mine_goal(1, epoch=self.epoch))["action"]
        self.assertEqual(action["targetRef"], "block-a")
        manager.result(self.receipt(action, "failed", "blocked", {"destroyed": {"block": "minecraft:log", "count": 0}}))
        view = manager.update(mine_goal(1, epoch=self.epoch))
        self.assertIsNotNone(view["action"])
        self.assertEqual(view["action"]["targetRef"], "block-b")
        manager.result(self.receipt(view["action"], "failed", "blocked", {"destroyed": {"block": "minecraft:log", "count": 0}}))
        view = manager.update(mine_goal(1, epoch=self.epoch))
        self.assertEqual(view["status"], "failed")
        self.assertEqual(view["skill"]["result"]["reason"], "blocked")

    def test_mine_blocked_candidate_then_accessible_succeeds(self):
        states, manager, self.epoch = self.create()
        states.update(snapshot(blocks={"block-a": block(4), "block-b": block(6)}, seq=1), True)
        action = manager.update(mine_goal(1, epoch=self.epoch))["action"]
        self.assertEqual(action["targetRef"], "block-a")
        manager.result(self.receipt(action, "failed", "blocked", {"destroyed": {"block": "minecraft:log", "count": 0}}))
        view = manager.update(mine_goal(1, epoch=self.epoch))
        self.assertEqual(view["action"]["targetRef"], "block-b")
        manager.result(self.receipt(view["action"], "succeeded", "completed", {"destroyed": {"block": "minecraft:log", "count": 1}}))
        self.assertEqual(manager.update(mine_goal(1, epoch=self.epoch))["status"], "completed")

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
        # A late running for an already-settled action is a valid observation but changes nothing.
        late = dict(body, status="running", reason="accepted", acquired={"item": "minecraft:log", "count": 0})
        self.assertEqual(manager.result(late), {"version": 2, "accepted": True})
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

    def test_control_lease_gates_action_issue(self):
        states, manager, self.epoch = self.create()
        states.update(snapshot(items={"item-a": drop(4), "item-b": drop(6)}, seq=1), True)
        action = self.accept(manager, 1, count=5)["action"]
        manager.result({"version": 2, "session": "world", "daemonEpoch": self.epoch, "goalRevision": 1,
                        "skillInstanceId": action["skillInstanceId"], "actionId": action["actionId"],
                        "actionSequence": action["actionSequence"], "status": "running", "reason": "accepted",
                        "acquired": {"item": "minecraft:log", "count": 0}})
        self.now[0] = 10.0  # the last control poll was at t=0, so the lease is stale
        states.update(snapshot(items={"item-a": drop(4), "item-b": drop(6)}, seq=2), True)
        manager.result({"version": 2, "session": "world", "daemonEpoch": self.epoch, "goalRevision": 1,
                        "skillInstanceId": action["skillInstanceId"], "actionId": action["actionId"],
                        "actionSequence": action["actionSequence"], "status": "succeeded", "reason": "completed",
                        "acquired": {"item": "minecraft:log", "count": 2}})
        self.assertIsNone(manager.active["world"].current)
        view = manager.update(goal(1, count=5, epoch=self.epoch))
        self.assertIsNotNone(view["action"])

    def test_lease_renews_only_on_accepted_update(self):
        states, manager, self.epoch = self.create()
        states.update(snapshot(items={"item-a": drop(4)}, seq=1), True)
        self.accept(manager, 1, count=5)
        self.assertEqual(manager.leases["world"], 0.0)
        self.now[0] = 3.0
        with self.assertRaises(SkillSyncError):
            manager.update(goal(1, item="minecraft:cobblestone", count=5, epoch=self.epoch))
        self.assertEqual(manager.leases["world"], 0.0)
        with self.assertRaises(SkillSyncError):
            manager.update(goal(0, count=5, epoch=self.epoch))
        self.assertEqual(manager.leases["world"], 0.0)
        self.now[0] = 4.0
        manager.update(goal(1, count=5, epoch=self.epoch))
        self.assertEqual(manager.leases["world"], 4.0)

    def test_receipt_revision_must_match_skill(self):
        states, manager, self.epoch = self.create()
        states.update(snapshot(items={"item-a": drop(4)}, seq=1), True)
        action = self.accept(manager, 1, count=2)["action"]
        bad = {"version": 2, "session": "world", "daemonEpoch": self.epoch, "goalRevision": 2,
               "skillInstanceId": action["skillInstanceId"], "actionId": action["actionId"],
               "actionSequence": action["actionSequence"], "status": "running", "reason": "accepted",
               "acquired": {"item": "minecraft:log", "count": 0}}
        with self.assertRaises(SkillSyncError):
            manager.result(bad)

    def test_late_receipt_after_terminal_is_recorded_but_result_immutable(self):
        states, manager, self.epoch = self.create()
        states.update(snapshot(items={"item-a": drop(4)}, seq=1), True)
        action = self.accept(manager, 1, count=5)["action"]
        running = {"version": 2, "session": "world", "daemonEpoch": self.epoch, "goalRevision": 1,
                   "skillInstanceId": action["skillInstanceId"], "actionId": action["actionId"],
                   "actionSequence": action["actionSequence"], "status": "running", "reason": "accepted",
                   "acquired": {"item": "minecraft:log", "count": 0}}
        manager.result(running)
        self.now[0] = SKILL_DEADLINE + 1
        manager.tick()
        view = manager.update(goal(1, count=5, epoch=self.epoch))
        self.assertEqual(view["status"], "failed")
        self.assertEqual(view["skill"]["result"]["reason"], "expired")
        self.assertFalse(view["skill"]["result"]["progress"]["complete"])
        late = {"version": 2, "session": "world", "daemonEpoch": self.epoch, "goalRevision": 1,
                "skillInstanceId": action["skillInstanceId"], "actionId": action["actionId"],
                "actionSequence": action["actionSequence"], "status": "succeeded", "reason": "completed",
                "acquired": {"item": "minecraft:log", "count": 2}}
        self.assertEqual(manager.result(late), {"version": 2, "accepted": True})
        self.assertEqual(manager.result(late), {"version": 2, "accepted": True})
        with self.assertRaises(SkillSyncError):
            manager.result(dict(late, acquired={"item": "minecraft:log", "count": 3}))
        view = manager.update(goal(1, count=5, epoch=self.epoch))
        self.assertEqual(view["skill"]["result"]["reason"], "expired")
        self.assertFalse(view["skill"]["result"]["progress"]["complete"])
        unknown = dict(late, actionId="never-issued", actionSequence=99)
        with self.assertRaises(SkillSyncError):
            manager.result(unknown)

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

    def test_cancel_then_partial_success_settles_cancelled(self):
        states, manager, self.epoch = self.create()
        states.update(snapshot(items={"item-a": drop(4)}, seq=1), True)
        action = self.accept(manager, 1, count=5)["action"]
        self.assertEqual(manager.update(goal(2, epoch=self.epoch, goal_type="null"))["status"], "idle")
        # The world already changed before the cancel was observed: the count is kept, but the
        # cancel that was established first wins and the Skill must never issue a new action.
        manager.result(self.receipt(action, "succeeded", "completed",
                                    {"acquired": {"item": "minecraft:log", "count": 2}}))
        status = manager.status({"version": 2, "session": "world", "daemonEpoch": self.epoch,
                                 "skillInstanceId": action["skillInstanceId"]})
        self.assertEqual(status["skill"]["result"]["status"], "cancelled")
        self.assertEqual(status["skill"]["result"]["progress"], {"requested": 5, "acquired": 2, "complete": True})
        self.assertIsNone(status["action"])

    def test_cancel_then_success_at_requested_stays_cancelled(self):
        states, manager, self.epoch = self.create()
        states.update(snapshot(items={"item-a": drop(4)}, seq=1), True)
        action = self.accept(manager, 1, count=2)["action"]
        self.assertEqual(action["maxCount"], 2)
        manager.update(goal(2, epoch=self.epoch, goal_type="null"))
        manager.result(self.receipt(action, "succeeded", "completed",
                                    {"acquired": {"item": "minecraft:log", "count": 2}}))
        status = manager.status({"version": 2, "session": "world", "daemonEpoch": self.epoch,
                                 "skillInstanceId": action["skillInstanceId"]})
        # Reaching the requested count after a cancel must not turn it into completed.
        self.assertEqual(status["skill"]["result"]["status"], "cancelled")
        self.assertEqual(status["skill"]["result"]["progress"]["acquired"], 2)

    def test_success_then_cancel_settles_cancelled(self):
        states, manager, self.epoch = self.create()
        states.update(snapshot(items={"item-a": drop(4), "item-b": drop(6)}, seq=1), True)
        action = self.accept(manager, 1, count=5)["action"]
        manager.result(self.receipt(action, "succeeded", "completed",
                                    {"acquired": {"item": "minecraft:log", "count": 2}}))
        manager.update(goal(2, epoch=self.epoch, goal_type="null"))
        self.now[0] = CANCEL_GRACE + 1
        manager.tick()
        status = manager.status({"version": 2, "session": "world", "daemonEpoch": self.epoch,
                                 "skillInstanceId": action["skillInstanceId"]})
        self.assertEqual(status["skill"]["result"]["status"], "cancelled")
        self.assertEqual(status["skill"]["result"]["progress"]["acquired"], 2)

    def test_replace_then_old_success_settles_old_cancelled_only(self):
        states, manager, self.epoch = self.create()
        states.update(snapshot(items={"item-a": drop(4)}, seq=1), True)
        action = self.accept(manager, 1, count=5)["action"]
        newer = manager.update(goal(2, epoch=self.epoch, count=5))
        self.assertIsNotNone(newer["action"])
        self.assertNotEqual(newer["skill"]["skillInstanceId"], action["skillInstanceId"])
        manager.result(self.receipt(action, "succeeded", "completed",
                                    {"acquired": {"item": "minecraft:log", "count": 2}}))
        old_status = manager.status({"version": 2, "session": "world", "daemonEpoch": self.epoch,
                                     "skillInstanceId": action["skillInstanceId"]})
        self.assertEqual(old_status["skill"]["result"]["status"], "cancelled")
        new_status = manager.status({"version": 2, "session": "world", "daemonEpoch": self.epoch,
                                     "skillInstanceId": newer["skill"]["skillInstanceId"]})
        self.assertIsNone(new_status["skill"]["result"])
        self.assertEqual(new_status["skill"]["phase"], "waiting_action")

    def test_cancelled_skill_stays_terminal_after_clock_advances(self):
        states, manager, self.epoch = self.create()
        states.update(snapshot(items={"item-a": drop(4)}, seq=1), True)
        action = self.accept(manager, 1, count=5)["action"]
        manager.update(goal(2, epoch=self.epoch, goal_type="null"))
        manager.result(self.receipt(action, "succeeded", "completed",
                                    {"acquired": {"item": "minecraft:log", "count": 2}}))
        self.now[0] = SKILL_DEADLINE + 80
        manager.tick()
        status = manager.status({"version": 2, "session": "world", "daemonEpoch": self.epoch,
                                 "skillInstanceId": action["skillInstanceId"]})
        self.assertEqual(status["skill"]["result"]["status"], "cancelled")
        self.assertEqual(status["skill"]["phase"], "terminal")

    def test_cancelled_skills_do_not_accumulate_in_other(self):
        states, manager, self.epoch = self.create()
        states.update(snapshot(items={"item-a": drop(4)}, seq=1), True)
        for i in range(130):
            revision = i * 2 + 1
            view = manager.update(goal(revision, epoch=self.epoch, count=5))
            action = view["action"]
            manager.update(goal(revision + 1, epoch=self.epoch, goal_type="null"))
            if action is not None:
                manager.result(self.receipt(action, "succeeded", "completed",
                                            {"acquired": {"item": "minecraft:log", "count": 2}},
                                            revision=revision))
        self.assertLessEqual(len(manager.other), MAX_TERMINAL)
        self.assertTrue(all(skill.phase == "terminal" for skill in manager.other.values()))

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
        for bad_block in ("minecraft:diamond_block", "", "nope"):
            with self.assertRaises(SkillRequestError):
                manager.update(mine_goal(1, block_name=bad_block, epoch=self.epoch))
        with self.assertRaises(SkillRequestError):
            manager.update(mine_goal(1, count=0, epoch=self.epoch))
        for bad_count in (2, 64):
            with self.assertRaises(SkillRequestError):
                manager.update(mine_goal(1, count=bad_count, epoch=self.epoch))
        with self.assertRaises(SkillRequestError):
            manager.update({"version": 2, "session": "world", "daemonEpoch": self.epoch, "goalRevision": 1,
                            "goal": {"type": "collect_drop", "target": {"item": "minecraft:log"},
                                     "count": 1, "constraints": [{"type": "avoid_entity"}]}})

    def test_null_cancel_is_idempotent_at_same_revision(self):
        states, manager, self.epoch = self.create()
        states.update(snapshot(items={"item-a": drop(4)}, seq=1), True)
        action = self.accept(manager, 1, count=5)["action"]
        first = manager.update(goal(1, epoch=self.epoch, goal_type="null"))
        self.assertEqual(first["status"], "idle")
        second = manager.update(goal(1, epoch=self.epoch, goal_type="null"))
        self.assertEqual(second["status"], "idle")
        self.assertEqual(second["goalRevision"], 1)
        self.assertIsNone(second["action"])

    def test_v2_rejects_legacy_string_goals_but_allows_null_cancel(self):
        states, manager, self.epoch = self.create()
        self.accept(manager, 1, count=2)
        for legacy in ("follow_owner", "stop", "look_at_owner", "pickup_item", "deposit_items", "nope"):
            with self.assertRaises(SkillRequestError):
                manager.update({"version": 2, "session": "world", "daemonEpoch": self.epoch,
                                "goalRevision": 2, "goal": legacy})
        # A null goal remains the only v2 Skill cancellation path.
        view = manager.update(goal(2, epoch=self.epoch, goal_type="null"))
        self.assertEqual(view["status"], "idle")
        self.assertEqual(view["action"], None)

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
            self.assertEqual(opened["capabilities"],
                             ["collect_drop_v1", "mine_v1", "collect_block_v1", "skill_terminal_social_v1"])
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
