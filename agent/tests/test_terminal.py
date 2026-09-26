import json
import sys
import threading
import unittest
from contextlib import closing
from http.client import HTTPConnection
from http.server import ThreadingHTTPServer
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))
from terminal_presentation import render_fallback, render_candidates, TARGET_LABELS
from terminal_events import TerminalEventStore, TerminalError
from skill_protocol import (SkillRequestError, validate_terminal_event, validate_terminal_query,
                            validate_present_request, validate_delivery_request, CAPABILITIES)
from social import SocialBrain, SocialError
from skills import SkillManager
from execution_registry import ExecutionRegistry
from state_cache import StateCache
from daemon import Handler

FIXTURE = json.loads((Path(__file__).resolve().parents[2] / "protocol" / "fixtures" / "skill-terminal-fallback.json")
                     .read_text(encoding="utf-8"))


def event(type_="collect_block", target=None, status="failed", reason="drop_unavailable",
          progress=None, terminal_id="t-1", skill_id="s-1", session="world", epoch="boot", revision=7, seq=1):
    if target is None:
        target = {"item": "minecraft:log"} if type_ == "collect_drop" else {"block": "minecraft:log"}
    if progress is None:
        progress = ({"requested": 5, "acquired": 2, "complete": True} if type_ == "collect_drop"
                    else {"requested": 1, "mined": 0, "complete": True} if type_ == "mine"
                    else {"requested": 5, "acquired": 2, "mined": 3, "complete": True})
    return {"version": 2, "category": "skill_terminal", "terminalId": terminal_id, "eventSequence": seq,
            "daemonEpoch": epoch, "session": session, "goalRevision": revision, "skillInstanceId": skill_id,
            "type": type_, "status": status, "reason": reason, "target": target, "progress": progress}


class _StubProvider:
    def __init__(self, variant=None, error=None):
        self.variant = variant
        self.error = error
        self.calls = 0
    def reply(self, messages): return "x"
    def reply_with_intent(self, messages): return {"reply": "x", "intent": "none"}
    def select_terminal(self, messages, variant_ids):
        self.calls += 1
        if self.error:
            raise SocialError(self.error)
        return self.variant if self.variant in variant_ids else variant_ids[0]


class _NoSelectProvider:
    def reply(self, messages): return "x"
    def reply_with_intent(self, messages): return {"reply": "x", "intent": "none"}


class PresentationTests(unittest.TestCase):
    def event(self):
        return {k: event()[k] for k in ("type", "target", "status", "reason", "progress")}

    def test_present_terminal_uses_provider_and_keeps_all_facts(self):
        ev = self.event()
        provider = _StubProvider(variant="calm")
        result = SocialBrain(provider).present_terminal("conv", ev)
        self.assertEqual(result["mode"], "social")
        self.assertEqual(result["variantId"], "calm")
        self.assertEqual(result["say"], {c["variantId"]: c["say"] for c in render_candidates(ev)}["calm"])
        self.assertIn("drop_unavailable", result["say"])
        self.assertNotIn("intent", result)

    def test_present_terminal_raises_so_callers_can_fall_back(self):
        for provider in (_StubProvider(error="provider_unavailable_or_timeout"), _NoSelectProvider()):
            with self.assertRaises(SocialError):
                SocialBrain(provider).present_terminal("conv", self.event())

    def test_present_terminal_is_busy_under_concurrent_call(self):
        brain = SocialBrain(_StubProvider(variant="friendly"))
        brain.lock.acquire()
        try:
            with self.assertRaises(SocialError):
                brain.present_terminal("conv", self.event())
        finally:
            brain.lock.release()


class FixtureTests(unittest.TestCase):
    def test_python_renderer_matches_shared_fixture(self):
        for case in FIXTURE["cases"]:
            ev = {k: case[k] for k in ("type", "target", "status", "reason", "progress")}
            self.assertEqual(render_fallback(ev), case["fallback"], case["id"])
            self.assertLessEqual(len(case["fallback"].encode("utf-16-le")) // 2, 512, case["id"])

    def test_python_candidates_match_shared_fixture(self):
        for case in FIXTURE["cases"]:
            ev = {k: case[k] for k in ("type", "target", "status", "reason", "progress")}
            candidates = render_candidates(ev)
            expected = case["candidates"]
            self.assertEqual(len(candidates), len(expected), case["id"])
            for got, want in zip(candidates, expected):
                self.assertEqual(got["variantId"], want["variantId"], case["id"])
                self.assertEqual(got["say"], want["say"], case["id"])
                self.assertLessEqual(len(got["say"].encode("utf-16-le")) // 2, 512, case["id"])

    def test_unknown_target_uses_registry_name(self):
        self.assertEqual(TARGET_LABELS.get("minecraft:diamond_ore"), "ダイヤ鉱石")
        ev = {"type": "mine", "target": {"block": "mod:custom"}, "status": "failed",
              "reason": "no_block_in_range", "progress": {"requested": 1, "mined": 0, "complete": True}}
        self.assertIn("mod:custom", render_fallback(ev))


class EventSchemaTests(unittest.TestCase):
    def test_valid_event_and_progress_union(self):
        parsed = validate_terminal_event(event())
        self.assertEqual(parsed["progress"], {"requested": 5, "acquired": 2, "mined": 3, "complete": True})
        self.assertEqual(parsed["type"], "collect_block")

    def test_bool_negative_and_unknown_keys_rejected(self):
        with self.assertRaises(SkillRequestError):
            validate_terminal_event(dict(event(), progress={"requested": 5, "acquired": True, "mined": 3, "complete": True}))
        with self.assertRaises(SkillRequestError):
            validate_terminal_event(dict(event(), progress={"requested": 5, "acquired": -1, "mined": 0, "complete": True}))
        with self.assertRaises(SkillRequestError):
            validate_terminal_event(dict(event(), extra=1))
        with self.assertRaises(SkillRequestError):
            validate_terminal_event(dict(event(), progress={"requested": 0, "acquired": 0, "mined": 0, "complete": True}))
        with self.assertRaises(SkillRequestError):
            validate_terminal_event(dict(event(), progress={"requested": 5, "acquired": 2, "complete": True}))
        with self.assertRaises(SkillRequestError):
            validate_terminal_event(dict(event(), status="running"))
        with self.assertRaises(SkillRequestError):
            # completed must carry the success metric at requested
            validate_terminal_event(dict(event(status="completed"), progress={"requested": 5, "acquired": 1, "mined": 3, "complete": True}))

    def test_query_and_delivery_validators(self):
        self.assertIsNone(validate_terminal_query({"version": 2, "daemonEpoch": "boot", "session": "world"})["afterSequence"])
        with self.assertRaises(SkillRequestError):
            validate_terminal_query({"version": 2, "daemonEpoch": "boot", "session": "world", "nope": 1})
        present = {"version": 2, "daemonEpoch": "boot", "session": "world", "skillInstanceId": "s-1",
                   "terminalId": "t-1", "conversationSession": "conv", "player": "Steve", "deliveryId": "d-1"}
        self.assertEqual(validate_present_request(present)["player"], "Steve")
        with self.assertRaises(SkillRequestError):
            validate_delivery_request(dict(present, outcome="displayed"))  # displayed needs variantId
        self.assertEqual(validate_delivery_request(dict(present, outcome="suppressed"))["outcome"], "suppressed")
        self.assertEqual(validate_delivery_request(dict(present, outcome="displayed", variantId="fallback"))["variantId"], "fallback")


class StoreTests(unittest.TestCase):
    def test_record_assigns_sequence_and_is_immutable(self):
        store = TerminalEventStore(clock=lambda: 0.0)
        base = event(skill_id="s-1", terminal_id="t-1")
        stored = store.record({k: base[k] for k in base if k != "eventSequence"})
        self.assertEqual(stored["eventSequence"], 1)
        stored2 = store.record({k: event(skill_id="s-2", terminal_id="t-2")[k] for k in base if k != "eventSequence"})
        self.assertEqual(stored2["eventSequence"], 2)
        # A later mutation of the caller's data must not change the stored snapshot.
        base["reason"] = "mutated"
        page = store.snapshot("boot", "world")
        self.assertEqual(page["events"][0]["reason"], "drop_unavailable")

    def test_old_revision_events_are_kept_and_duplicate_poll_is_stable(self):
        store = TerminalEventStore(clock=lambda: 0.0)
        snap = {k: event(revision=3)[k] for k in event() if k != "eventSequence"}
        store.record(snap)
        first = store.snapshot("boot", "world")
        second = store.snapshot("boot", "world")
        self.assertEqual(first["events"], second["events"])   # read-only, deduped by identity
        self.assertEqual(first["events"][0]["goalRevision"], 3)
        self.assertFalse(first["hasMore"])

    def test_same_identity_different_payload_conflicts(self):
        store = TerminalEventStore(clock=lambda: 0.0)
        snap = {k: event()[k] for k in event() if k != "eventSequence"}
        store.record(snap)
        with self.assertRaises(TerminalError):
            store.record(dict(snap, status="cancelled"))

    def test_duplicate_record_returns_existing_and_does_not_consume_sequence(self):
        store = TerminalEventStore(clock=lambda: 0.0)
        snap = {k: event(skill_id="s-1", terminal_id="t-1")[k] for k in event() if k != "eventSequence"}
        first = store.record(snap)
        duplicate = store.record(snap)
        self.assertEqual(first, duplicate)
        self.assertEqual(duplicate["eventSequence"], 1)
        second = store.record({k: event(skill_id="s-2", terminal_id="t-2")[k] for k in event() if k != "eventSequence"})
        self.assertEqual(second["eventSequence"], 2)   # sequence stays consecutive

    def test_nested_mutation_of_the_callers_snapshot_does_not_change_the_store(self):
        store = TerminalEventStore(clock=lambda: 0.0)
        snap = {k: event(skill_id="s-1", terminal_id="t-1")[k] for k in event() if k != "eventSequence"}
        store.record(snap)
        snap["progress"]["acquired"] = 999
        snap["target"]["block"] = "minecraft:stone"
        page = store.snapshot("boot", "world")
        self.assertEqual(page["events"][0]["progress"]["acquired"], 2)
        self.assertEqual(page["events"][0]["target"]["block"], "minecraft:log")

    def test_closed_identity_still_detects_conflicting_payload(self):
        store = TerminalEventStore(clock=lambda: 0.0)
        snap = {k: event(skill_id="s-1", terminal_id="t-1")[k] for k in event() if k != "eventSequence"}
        store.record(snap)
        store.present("boot", "world", "s-1", "t-1", "conv", "Steve", "d-1")
        store.deliver("boot", "world", "s-1", "t-1", "conv", "Steve", "d-1", "displayed", "fallback")
        self.assertIsNone(store.record(snap))                       # same payload: no regeneration
        self.assertEqual(store.snapshot("boot", "world", after_sequence=0)["events"], [])
        with self.assertRaises(TerminalError):                      # different payload after ACK
            store.record(dict(snap, status="cancelled"))

    def test_presentation_ledger_is_bounded_and_releases_say(self):
        store = TerminalEventStore(clock=lambda: 0.0)
        for i in range(130):
            snap = {k: event(skill_id="s-%d" % i, terminal_id="t-%d" % i)[k] for k in event()}
            snap.pop("eventSequence", None)
            store.record(snap)
            store.present("boot", "world", "s-%d" % i, "t-%d" % i, "conv", "Steve", "d-%d" % i)
        self.assertLessEqual(len(store.presentations), 100)
        self.assertLessEqual(len(store.closed), 100)
        # A delivered entry leaves the ledger and releases the heavy say.
        snap = {k: event(skill_id="sf", terminal_id="tf")[k] for k in event()}
        snap.pop("eventSequence", None)
        store.record(snap)
        store.present("boot", "world", "sf", "tf", "conv", "Steve", "df")
        self.assertIn("say", store.presentations[("boot", "world", "sf", "tf")])
        store.deliver("boot", "world", "sf", "tf", "conv", "Steve", "df", "displayed", "fallback")
        self.assertNotIn(("boot", "world", "sf", "tf"), store.presentations)
        self.assertNotIn("say", store.closed[("boot", "world", "sf", "tf")])

    def test_closed_identity_survives_exact_ledger_eviction(self):
        store = TerminalEventStore(clock=lambda: 0.0)
        a = {k: event(skill_id="sA", terminal_id="tA")[k] for k in event() if k != "eventSequence"}
        store.record(a)
        store.present("boot", "world", "sA", "tA", "conv", "Steve", "dA")
        store.deliver("boot", "world", "sA", "tA", "conv", "Steve", "dA", "displayed", "fallback")
        identity = ("boot", "world", "sA", "tA")
        for i in range(150):   # close more terminals than the exact closed ledger can hold
            snap = {k: event(skill_id="s-%d" % i, terminal_id="t-%d" % i)[k] for k in event()}
            snap.pop("eventSequence", None)
            store.record(snap)
            store.present("boot", "world", "s-%d" % i, "t-%d" % i, "conv", "Steve", "d-%d" % i)
            store.deliver("boot", "world", "s-%d" % i, "t-%d" % i, "conv", "Steve", "d-%d" % i, "displayed", "fallback")
        self.assertNotIn(identity, store.closed)          # the exact record was evicted...
        self.assertIsNone(store.record(a))                 # ...but the identity is never regenerated
        self.assertEqual(store.snapshot("boot", "world", after_sequence=0)["events"], [])
        with self.assertRaises(TerminalError):             # a changed payload is still a conflict
            store.record(dict(a, status="cancelled"))

    def test_ack_closes_the_outbox_entry_and_never_regenerates_it(self):
        for outcome, variant in (("displayed", "fallback"), ("suppressed", None)):
            store = TerminalEventStore(clock=lambda: 0.0)
            snap = {k: event(skill_id="s-1", terminal_id="t-1")[k] for k in event() if k != "eventSequence"}
            store.record(snap)
            self.assertEqual(len(store.snapshot("boot", "world", after_sequence=0)["events"]), 1)
            present = store.present("boot", "world", "s-1", "t-1", "conv", "Steve", "d-1")
            self.assertEqual(present["mode"], "fallback")
            store.deliver("boot", "world", "s-1", "t-1", "conv", "Steve", "d-1", outcome, variant)
            # ACKed terminal is gone from a full re-read, so a lost cursor cannot re-deliver it.
            self.assertEqual(store.snapshot("boot", "world", after_sequence=0)["events"], [])
            store.deliver("boot", "world", "s-1", "t-1", "conv", "Steve", "d-1", outcome, variant)   # idempotent
            self.assertIsNone(store.record(snap))   # closed identity is not regenerated
            self.assertEqual(store.snapshot("boot", "world", after_sequence=0)["events"], [])
            with self.assertRaises(TerminalError):   # conflicting ACK still rejected
                other = "fallback" if outcome == "suppressed" else None
                store.deliver("boot", "world", "s-1", "t-1", "conv", "Steve", "d-1",
                              "suppressed" if outcome == "displayed" else "displayed", other)

    def test_present_and_deliver_idempotency_and_conflicts(self):
        store = TerminalEventStore(clock=lambda: 0.0)
        snap = {k: event(skill_id="s-1", terminal_id="t-1")[k] for k in event() if k != "eventSequence"}
        store.record(snap)
        present = store.present("boot", "world", "s-1", "t-1", "conv", "Steve", "d-1")
        self.assertEqual(present["mode"], "fallback")
        self.assertEqual(present["say"], render_fallback(snap))
        self.assertEqual(store.present("boot", "world", "s-1", "t-1", "conv", "Steve", "d-1"), present)
        with self.assertRaises(TerminalError):   # rebinding to another player
            store.present("boot", "world", "s-1", "t-1", "conv", "Alex", "d-1")
        with self.assertRaises(TerminalError):   # a different terminalId for the same Skill
            store.present("boot", "world", "s-1", "t-2", "conv", "Steve", "d-2")
        with self.assertRaises(TerminalError):
            store.present("boot", "world", "s-9", "t-9", "conv", "Steve", "d-1")   # unknown terminal
        store.deliver("boot", "world", "s-1", "t-1", "conv", "Steve", "d-1", "displayed", "fallback")
        store.deliver("boot", "world", "s-1", "t-1", "conv", "Steve", "d-1", "displayed", "fallback")   # idempotent
        with self.assertRaises(TerminalError):
            store.deliver("boot", "world", "s-1", "t-1", "conv", "Steve", "d-1", "suppressed", None)


def snapshot_state(seq=0, session="world", items=None, blocks=None):
    return {"version": 1, "session": session, "sequence": seq,
            "state": {"dimension": 0,
                      "owner": {"position": [0, 64, 0], "health": 20, "inventory": {}},
                      "companion": {"id": "companion", "position": [0, 64, 0], "health": 20,
                                    "task": "idle", "result": "none", "inventory": {}},
                      "hostiles": {}, "items": items or {}, "blocks": blocks or {}}}


class SkillIntegrationTests(unittest.TestCase):
    def create(self, store):
        self.now = [0.0]
        clock = lambda: self.now[0]
        states = StateCache(clock=clock)
        states.update(snapshot_state(seq=0), True)
        registry = ExecutionRegistry(clock=clock)
        manager = SkillManager(states, registry, clock=clock, terminal_store=store)
        opened = manager.open({"version": 2, "session": "world"})
        self.epoch = opened["daemonEpoch"]
        return states, manager

    def test_completed_skill_emits_one_event(self):
        store = TerminalEventStore(clock=lambda: 0.0)
        states, manager = self.create(store)
        states.update(snapshot_state(seq=1, blocks={"b": {"type": "minecraft:log", "distance": 4}}), True)
        view = manager.update({"version": 2, "session": "world", "daemonEpoch": self.epoch, "goalRevision": 1,
                               "goal": {"type": "mine", "target": {"block": "minecraft:log"}, "count": 1, "constraints": []}})
        action = view["action"]
        manager.result({"version": 2, "session": "world", "daemonEpoch": self.epoch, "goalRevision": 1,
                        "skillInstanceId": action["skillInstanceId"], "actionId": action["actionId"],
                        "actionSequence": action["actionSequence"], "status": "succeeded", "reason": "completed",
                        "destroyed": {"block": "minecraft:log", "count": 1}})
        page = store.snapshot(self.epoch, "world")
        self.assertEqual(len(page["events"]), 1)
        ev = page["events"][0]
        self.assertEqual((ev["type"], ev["status"], ev["reason"]), ("mine", "completed", "completed"))
        self.assertEqual(ev["progress"], {"requested": 1, "mined": 1, "complete": True})
        # A duplicate receipt never creates a second event.
        manager.result({"version": 2, "session": "world", "daemonEpoch": self.epoch, "goalRevision": 1,
                        "skillInstanceId": action["skillInstanceId"], "actionId": action["actionId"],
                        "actionSequence": action["actionSequence"], "status": "succeeded", "reason": "completed",
                        "destroyed": {"block": "minecraft:log", "count": 1}})
        self.assertEqual(len(store.snapshot(self.epoch, "world")["events"]), 1)

    def test_cancel_after_partial_emits_cancelled_with_partial(self):
        store = TerminalEventStore(clock=lambda: 0.0)
        states, manager = self.create(store)
        states.update(snapshot_state(seq=1, items={"i": {"type": "minecraft:log", "distance": 4}}), True)
        view = manager.update({"version": 2, "session": "world", "daemonEpoch": self.epoch, "goalRevision": 1,
                               "goal": {"type": "collect_drop", "target": {"item": "minecraft:log"}, "count": 5, "constraints": []}})
        action = view["action"]
        manager.update({"version": 2, "session": "world", "daemonEpoch": self.epoch, "goalRevision": 2, "goal": None})
        manager.result({"version": 2, "session": "world", "daemonEpoch": self.epoch, "goalRevision": 1,
                        "skillInstanceId": action["skillInstanceId"], "actionId": action["actionId"],
                        "actionSequence": action["actionSequence"], "status": "succeeded", "reason": "completed",
                        "acquired": {"item": "minecraft:log", "count": 2}})
        ev = store.snapshot(self.epoch, "world")["events"][0]
        self.assertEqual(ev["status"], "cancelled")
        self.assertEqual(ev["progress"], {"requested": 5, "acquired": 2, "complete": True})

    def test_open_advertises_terminal_capability(self):
        store = TerminalEventStore(clock=lambda: 0.0)
        _, manager = self.create(store)
        opened = manager.open({"version": 2, "session": "world"})
        self.assertIn("skill_terminal_social_v1", CAPABILITIES)
        self.assertIn("skill_terminal_social_v1", opened["capabilities"])


class HttpTests(unittest.TestCase):
    def setUp(self):
        self.clock = [0.0]
        states = StateCache(clock=lambda: self.clock[0])
        states.update(snapshot_state(seq=0), True)
        registry = ExecutionRegistry(clock=lambda: self.clock[0])
        self.store = TerminalEventStore(clock=lambda: self.clock[0])
        self.manager = SkillManager(states, registry, clock=lambda: self.clock[0], terminal_store=self.store)
        self.epoch = self.manager.open({"version": 2, "session": "world"})["daemonEpoch"]
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.server.skills = self.manager
        self.server.terminals = self.store
        self.server.registry = registry
        self.worker = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.worker.start()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.worker.join()

    def post(self, path, data):
        with closing(HTTPConnection(*self.server.server_address, timeout=2)) as conn:
            conn.request("POST", path, json.dumps(data), {"Content-Type": "application/json"})
            response = conn.getresponse()
            return response.status, json.loads(response.read())

    def test_terminal_endpoints_lifecycle(self):
        snap = {k: event(skill_id="s-1", terminal_id="t-1", epoch=self.epoch)[k] for k in event() if k != "eventSequence"}
        self.store.record(snap)
        status, page = self.post("/v2/terminal-events",
                                 {"version": 2, "daemonEpoch": self.epoch, "session": "world"})
        self.assertEqual((status, len(page["events"])), (200, 1))
        present = {"version": 2, "daemonEpoch": self.epoch, "session": "world", "skillInstanceId": "s-1",
                   "terminalId": "t-1", "conversationSession": "conv", "player": "Steve", "deliveryId": "d-1"}
        status, said = self.post("/v2/social/skill-terminal", present)
        self.assertEqual((status, said["mode"], said["variantId"]), (200, "fallback", "fallback"))
        self.assertEqual(said["say"], render_fallback(snap))
        status, ack = self.post("/v2/social/terminal-delivery", dict(present, outcome="displayed", variantId="fallback"))
        self.assertEqual((status, ack["accepted"]), (200, True))
        self.assertEqual(self.post("/v2/terminal-events", {"version": 2, "daemonEpoch": "old", "session": "world"})[0], 409)

    def test_present_uses_provider_once_then_returns_ready(self):
        provider = _StubProvider(variant="friendly")
        self.server.brain = SocialBrain(provider)
        snap = {k: event(skill_id="s-1", terminal_id="t-1", epoch=self.epoch)[k] for k in event() if k != "eventSequence"}
        self.store.record(snap)
        present = {"version": 2, "daemonEpoch": self.epoch, "session": "world", "skillInstanceId": "s-1",
                   "terminalId": "t-1", "conversationSession": "conv", "player": "Steve", "deliveryId": "d-1"}
        status, said = self.post("/v2/social/skill-terminal", present)
        self.assertEqual((status, said["mode"], said["variantId"]), (200, "social", "friendly"))
        self.assertEqual(said["say"], {c["variantId"]: c["say"] for c in render_candidates(snap)}["friendly"])
        status, again = self.post("/v2/social/skill-terminal", present)
        self.assertEqual((again["mode"], again["say"]), ("social", said["say"]))
        self.assertEqual(provider.calls, 1)   # a duplicate present never calls the provider again

    def test_present_falls_back_when_provider_fails(self):
        self.server.brain = SocialBrain(_StubProvider(error="provider_unavailable_or_timeout"))
        snap = {k: event(skill_id="s-2", terminal_id="t-2", epoch=self.epoch)[k] for k in event() if k != "eventSequence"}
        self.store.record(snap)
        present = {"version": 2, "daemonEpoch": self.epoch, "session": "world", "skillInstanceId": "s-2",
                   "terminalId": "t-2", "conversationSession": "conv", "player": "Steve", "deliveryId": "d-2"}
        status, said = self.post("/v2/social/skill-terminal", present)
        self.assertEqual((status, said["mode"], said["variantId"]), (200, "fallback", "fallback"))
        self.assertEqual(said["say"], render_fallback(snap))


if __name__ == "__main__":
    unittest.main()
