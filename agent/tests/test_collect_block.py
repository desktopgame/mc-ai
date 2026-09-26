import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))
from skills import SkillManager, DROP_WINDOW, SEARCH_WINDOW
from execution_registry import ExecutionRegistry
from state_cache import StateCache
from skill_protocol import SkillRequestError, SkillSyncError


def snapshot(items=None, blocks=None, inventory=None, seq=0, session="world", companion="companion"):
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


def goal(revision, block_name="minecraft:log", count=5, session="world", epoch=None):
    return {"version": 2, "session": session, "daemonEpoch": epoch, "goalRevision": revision,
            "goal": {"type": "collect_block", "target": {"block": block_name}, "count": count, "constraints": []}}


class CollectBlockTests(unittest.TestCase):
    def create(self):
        self.now = [0.0]
        clock = lambda: self.now[0]
        states = StateCache(clock=clock)
        states.update(snapshot(seq=0), True)
        registry = ExecutionRegistry(clock=clock)
        manager = SkillManager(states, registry, clock=clock)
        opened = manager.open({"version": 2, "session": "world"})
        self.epoch = opened["daemonEpoch"]
        return states, manager

    def accept(self, manager, revision, count=5, block_name="minecraft:log"):
        return manager.update(goal(revision, block_name=block_name, count=count, epoch=self.epoch))

    def mine(self, action, status, reason, count=0, revision=1):
        return {"version": 2, "session": "world", "daemonEpoch": self.epoch, "goalRevision": revision,
                "skillInstanceId": action["skillInstanceId"], "actionId": action["actionId"],
                "actionSequence": action["actionSequence"], "status": status, "reason": reason,
                "destroyed": {"block": "minecraft:log", "count": count}}

    def pickup(self, action, status, reason, count=0, revision=1):
        return {"version": 2, "session": "world", "daemonEpoch": self.epoch, "goalRevision": revision,
                "skillInstanceId": action["skillInstanceId"], "actionId": action["actionId"],
                "actionSequence": action["actionSequence"], "status": status, "reason": reason,
                "acquired": {"item": "minecraft:log", "count": count}}

    # ---- goal validation -------------------------------------------------
    def test_goal_validation(self):
        states, manager = self.create()
        for bad_count in (0, 65, True, 2.5, "3"):
            with self.assertRaises(SkillRequestError):
                manager.update(goal(1, count=bad_count, epoch=self.epoch))
        with self.assertRaises(SkillRequestError):
            manager.update(goal(1, block_name="minecraft:diamond_block", epoch=self.epoch))
        with self.assertRaises(SkillRequestError):
            manager.update({"version": 2, "session": "world", "daemonEpoch": self.epoch, "goalRevision": 1,
                            "goal": {"type": "collect_block", "target": {"block": "minecraft:log"},
                                     "count": 1, "constraints": [{"type": "avoid"}]}})
        with self.assertRaises(SkillRequestError):
            manager.update({"version": 2, "session": "world", "daemonEpoch": self.epoch, "goalRevision": 1,
                            "goal": {"type": "collect_block", "target": {"block": "minecraft:log"},
                                     "count": 1, "extra": True}})

    # ---- alternation and success semantics -------------------------------
    def test_mine_success_alone_does_not_complete(self):
        states, manager = self.create()
        states.update(snapshot(blocks={"block-a": block(4)}, seq=1), True)
        view = self.accept(manager, 1, count=1)
        action = view["action"]
        self.assertEqual(action["type"], "mine_target")
        manager.result(self.mine(action, "succeeded", "completed", 1))
        view = manager.update(goal(1, count=1, epoch=self.epoch))
        self.assertEqual(view["status"], "running")
        self.assertEqual(view["skill"]["progress"], {"requested": 1, "acquired": 0, "mined": 1, "complete": True})
        self.assertIsNone(view["action"])
        self.now[0] = DROP_WINDOW + 1
        manager.tick()
        view = manager.update(goal(1, count=1, epoch=self.epoch))
        self.assertEqual(view["status"], "failed")
        self.assertEqual(view["skill"]["result"]["reason"], "drop_unavailable")
        self.assertEqual(view["skill"]["result"]["progress"], {"requested": 1, "acquired": 0, "mined": 1, "complete": True})

    def test_mine_then_pickup_completes_with_both_counters(self):
        states, manager = self.create()
        states.update(snapshot(blocks={"block-a": block(4)}, seq=1), True)
        action = self.accept(manager, 1, count=1)["action"]
        manager.result(self.mine(action, "succeeded", "completed", 1))
        states.update(snapshot(items={"item-b": drop(4)}, seq=2), True)
        view = manager.update(goal(1, count=1, epoch=self.epoch))
        pickup = view["action"]
        self.assertEqual(pickup["type"], "pickup_target")
        self.assertEqual(pickup["maxCount"], 1)
        manager.result(self.pickup(pickup, "succeeded", "completed", 1))
        view = manager.update(goal(1, count=1, epoch=self.epoch))
        self.assertEqual(view["status"], "completed")
        self.assertEqual(view["skill"]["result"]["progress"], {"requested": 1, "acquired": 1, "mined": 1, "complete": True})

    def test_drop_first_then_mine_then_recover(self):
        states, manager = self.create()
        states.update(snapshot(items={"item-a": drop(4)}, blocks={"block-a": block(6)}, seq=1), True)
        view = self.accept(manager, 1, count=5)
        first = view["action"]
        self.assertEqual(first["type"], "pickup_target")
        self.assertEqual(first["maxCount"], 5)
        manager.result(self.pickup(first, "succeeded", "completed", 2))
        # The first drop is gone; a block is still available, so the Skill mines next.
        states.update(snapshot(blocks={"block-a": block(6)}, seq=2), True)
        view = manager.update(goal(1, count=5, epoch=self.epoch))
        mine = view["action"]
        self.assertEqual(mine["type"], "mine_target")
        manager.result(self.mine(mine, "succeeded", "completed", 1))
        states.update(snapshot(items={"item-b": drop(4)}, seq=3), True)
        view = manager.update(goal(1, count=5, epoch=self.epoch))
        recover = view["action"]
        self.assertEqual(recover["type"], "pickup_target")
        self.assertEqual(recover["maxCount"], 3)
        manager.result(self.pickup(recover, "succeeded", "completed", 3))
        view = manager.update(goal(1, count=5, epoch=self.epoch))
        self.assertEqual(view["status"], "completed")
        self.assertEqual(view["skill"]["result"]["progress"], {"requested": 5, "acquired": 5, "mined": 1, "complete": True})

    def test_one_current_action_only(self):
        states, manager = self.create()
        states.update(snapshot(items={"item-a": drop(4)}, blocks={"block-a": block(6)}, seq=1), True)
        first = self.accept(manager, 1, count=5)["action"]
        again = manager.update(goal(1, count=5, epoch=self.epoch))["action"]
        self.assertEqual(again["actionId"], first["actionId"])
        self.assertEqual(again["actionSequence"], first["actionSequence"])
        self.assertEqual(again["skillInstanceId"], first["skillInstanceId"])

    def test_starting_inventory_is_not_counted(self):
        states, manager = self.create()
        states.update(snapshot(items={}, blocks={"block-a": block(4)}, inventory={"minecraft:log": 10}, seq=1), True)
        view = self.accept(manager, 1, count=3)
        self.assertEqual(view["status"], "running")
        self.assertEqual(view["skill"]["progress"]["acquired"], 0)
        self.assertIsNotNone(view["action"])

    # ---- receipt validation ----------------------------------------------
    def test_mixed_and_duplicate_receipts(self):
        states, manager = self.create()
        states.update(snapshot(items={"item-a": drop(4)}, blocks={"block-a": block(6)}, seq=1), True)
        action = self.accept(manager, 1, count=2)["action"]
        self.assertEqual(action["type"], "pickup_target")
        with self.assertRaises(SkillSyncError):
            manager.result(self.mine(action, "succeeded", "completed", 1))
        with self.assertRaises(SkillSyncError):
            manager.result(self.pickup(action, "succeeded", "completed", 5))  # over maxCount
        with self.assertRaises(SkillSyncError):
            manager.result(dict(self.pickup(action, "succeeded", "completed", 2), actionSequence=99))
        body = self.pickup(action, "succeeded", "completed", 2)
        manager.result(body)
        self.assertEqual(manager.result(body), {"version": 2, "accepted": True})     # idempotent
        with self.assertRaises(SkillSyncError):
            manager.result(dict(body, acquired={"item": "minecraft:log", "count": 1}))  # conflicting
        self.assertEqual(manager.update(goal(1, count=2, epoch=self.epoch))["status"], "completed")

    def test_out_of_order_mine_receipt_after_pickup_stage(self):
        states, manager = self.create()
        states.update(snapshot(blocks={"block-a": block(4)}, seq=1), True)
        mine = self.accept(manager, 1, count=1)["action"]
        manager.result(self.mine(mine, "succeeded", "completed", 1))
        states.update(snapshot(items={"item-b": drop(4)}, seq=2), True)
        pickup = manager.update(goal(1, count=1, epoch=self.epoch))["action"]
        # Replaying the settled mine receipt is only ACKed; counters do not move.
        self.assertEqual(manager.result(self.mine(mine, "succeeded", "completed", 1)), {"version": 2, "accepted": True})
        self.assertEqual(manager.status({"version": 2, "session": "world", "daemonEpoch": self.epoch,
                                         "skillInstanceId": pickup["skillInstanceId"]})["skill"]["progress"]["mined"], 1)

    # ---- failures and cancellation ---------------------------------------
    def test_mine_blocked_skips_until_exhausted(self):
        states, manager = self.create()
        states.update(snapshot(blocks={"block-a": block(4), "block-b": block(6)}, seq=1), True)
        first = self.accept(manager, 1, count=1)["action"]
        self.assertEqual(first["targetRef"], "block-a")
        manager.result(self.mine(first, "failed", "blocked", 0))
        view = manager.update(goal(1, count=1, epoch=self.epoch))
        self.assertEqual(view["action"]["targetRef"], "block-b")
        manager.result(self.mine(view["action"], "failed", "blocked", 0))
        view = manager.update(goal(1, count=1, epoch=self.epoch))
        self.assertEqual(view["status"], "failed")
        self.assertEqual(view["skill"]["result"]["reason"], "blocked")

    def test_tool_unavailable_is_terminal(self):
        states, manager = self.create()
        states.update(snapshot(blocks={"block-a": block(4)}, seq=1), True)
        action = self.accept(manager, 1, count=1)["action"]
        manager.result(self.mine(action, "failed", "tool_unavailable", 0))
        view = manager.update(goal(1, count=1, epoch=self.epoch))
        self.assertEqual(view["status"], "failed")
        self.assertEqual(view["skill"]["result"]["reason"], "tool_unavailable")

    def test_cancel_after_mine_keeps_mined(self):
        states, manager = self.create()
        states.update(snapshot(blocks={"block-a": block(4)}, seq=1), True)
        action = self.accept(manager, 1, count=5)["action"]
        manager.update({"version": 2, "session": "world", "daemonEpoch": self.epoch, "goalRevision": 2, "goal": None})
        manager.result(self.mine(action, "succeeded", "completed", 1))
        status = manager.status({"version": 2, "session": "world", "daemonEpoch": self.epoch,
                                 "skillInstanceId": action["skillInstanceId"]})
        self.assertEqual(status["skill"]["result"]["status"], "cancelled")
        self.assertEqual(status["skill"]["result"]["progress"], {"requested": 5, "acquired": 0, "mined": 1, "complete": True})

    def test_cancel_after_pickup_keeps_acquired(self):
        states, manager = self.create()
        states.update(snapshot(items={"item-a": drop(4)}, seq=1), True)
        action = self.accept(manager, 1, count=5)["action"]
        manager.update({"version": 2, "session": "world", "daemonEpoch": self.epoch, "goalRevision": 2, "goal": None})
        manager.result(self.pickup(action, "succeeded", "completed", 2))
        status = manager.status({"version": 2, "session": "world", "daemonEpoch": self.epoch,
                                 "skillInstanceId": action["skillInstanceId"]})
        self.assertEqual(status["skill"]["result"]["status"], "cancelled")
        self.assertEqual(status["skill"]["result"]["progress"]["acquired"], 2)

    def test_no_candidates_reports_no_block_in_range(self):
        states, manager = self.create()
        view = self.accept(manager, 1, count=1)
        self.assertIsNone(view["action"])
        self.now[0] = SEARCH_WINDOW + 1
        manager.tick()
        view = manager.update(goal(1, count=1, epoch=self.epoch))
        self.assertEqual(view["status"], "failed")
        self.assertEqual(view["skill"]["result"]["reason"], "no_block_in_range")

    def test_full_mixed_sequence_and_old_replay(self):
        states, manager = self.create()
        states.update(snapshot(blocks={"block-a": block(4)}, seq=1), True)
        mine1 = self.accept(manager, 1, count=2)["action"]
        self.assertEqual(mine1["actionSequence"], 1)
        manager.result(self.mine(mine1, "succeeded", "completed", 1))
        states.update(snapshot(items={"d1": drop(4)}, seq=2), True)
        pick1 = manager.update(goal(1, count=2, epoch=self.epoch))["action"]
        self.assertEqual((pick1["type"], pick1["actionSequence"], pick1["maxCount"]), ("pickup_target", 2, 2))
        states.update(snapshot(blocks={"block-b": block(4)}, seq=3), True)
        manager.result(self.pickup(pick1, "succeeded", "completed", 1))
        mine2 = manager.update(goal(1, count=2, epoch=self.epoch))["action"]
        self.assertEqual((mine2["type"], mine2["actionSequence"]), ("mine_target", 3))
        manager.result(self.mine(mine2, "succeeded", "completed", 1))
        states.update(snapshot(items={"d2": drop(4)}, seq=4), True)
        pick2 = manager.update(goal(1, count=2, epoch=self.epoch))["action"]
        self.assertEqual((pick2["type"], pick2["actionSequence"], pick2["maxCount"]), ("pickup_target", 4, 1))
        manager.result(self.pickup(pick2, "succeeded", "completed", 1))
        view = manager.update(goal(1, count=2, epoch=self.epoch))
        self.assertEqual(view["status"], "completed")
        self.assertEqual(view["skill"]["result"]["progress"], {"requested": 2, "acquired": 2, "mined": 2, "complete": True})
        # Replaying an old settled action only ACKs; neither counter moves.
        self.assertEqual(manager.result(self.mine(mine1, "succeeded", "completed", 1)), {"version": 2, "accepted": True})
        status = manager.status({"version": 2, "session": "world", "daemonEpoch": self.epoch,
                                 "skillInstanceId": mine1["skillInstanceId"]})
        self.assertEqual(status["skill"]["result"]["progress"]["mined"], 2)

    def test_pickup_max_count_is_bounded_by_remaining(self):
        states, manager = self.create()
        states.update(snapshot(items={"item-a": drop(4)}, seq=1), True)
        view = self.accept(manager, 1, count=64)
        self.assertEqual(view["action"]["type"], "pickup_target")
        self.assertEqual(view["action"]["maxCount"], 64)

    def test_retry_exhausted_after_three_failures(self):
        states, manager = self.create()
        states.update(snapshot(blocks={"block-a": block(4), "block-b": block(6), "block-c": block(8), "block-d": block(10)}, seq=1), True)
        action = self.accept(manager, 1, count=1)["action"]
        for _ in range(3):
            manager.result(self.mine(action, "failed", "target_lost", 0))
            action = manager.update(goal(1, count=1, epoch=self.epoch))["action"]
        view = manager.update(goal(1, count=1, epoch=self.epoch))
        self.assertEqual(view["skill"]["result"]["reason"], "retry_exhausted")


if __name__ == "__main__":
    unittest.main()
