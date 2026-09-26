"""Bounded goal lifecycle. Provider work never holds the control/result lock."""
import copy
import logging
import threading
import time
import uuid
from collections import OrderedDict

from decision import GOALS, DecisionError, sanitize, validate_decision
from state_cache import SyncError, identity
from context_budget import BudgetExceeded

TERMINAL = {"succeeded", "failed", "cancelled"}
REASONS = {"accepted", "completed", "replaced", "stopped", "unsafe_state", "path_not_found",
           "owner_unavailable", "companion_unavailable", "disconnected", "expired",
           "no_item_in_range", "inventory_full"}
LOG = logging.getLogger("mcai.goals")


def envelope(data, keys):
    if not isinstance(data, dict) or set(data) != set(keys) | {"version", "session", "goalRevision"}:
        raise ValueError("invalid_goal_envelope")
    if type(data["version"]) is not int or data["version"] != 1:
        raise ValueError("invalid_version")
    identity(data["session"])
    if type(data["goalRevision"]) is not int or not 0 <= data["goalRevision"] <= 2147483647:
        raise ValueError("invalid_revision")


class GoalManager:
    def __init__(self, states, decisions, clock=time.monotonic):
        self.states, self.decisions, self.clock = states, decisions, clock
        self.lock = threading.Lock()
        self.entries = OrderedDict()
        self.pending = OrderedDict()
        self.results = OrderedDict()
        self.worker = None

    def _state(self, session):
        cached = self.states.view({"version": 1, "session": session})
        if cached["stale"]: raise SyncError("stale_state")
        return cached["state"]

    def _input(self, goal, state):
        if state["companion"] is None: raise SyncError("companion_unavailable")
        items = state["items"]
        nearest = min((entry["distance"] for entry in items.values()), default=None)
        return {"version": 1, "goal": {"type": goal}, "availableActions": ["follow", "stop", "look", "pickup"],
                "state": {"companion": {k: state["companion"][k] for k in ("health", "position")},
                          "owner": {"position": state["owner"]["position"]},
                          "items": {"count": len(items), "nearestDistance": nearest}}}

    def update(self, data):
        envelope(data, {"goal"})
        goal = data["goal"]
        if goal is not None and (type(goal) is not str or goal not in GOALS):
            raise ValueError("unsupported_goal")
        session, revision = data["session"], data["goalRevision"]
        with self.lock:
            old = self.entries.get(session)
            if old and revision < old["revision"]: raise SyncError("stale_goal")
            if old and revision == old["revision"]:
                if goal != old["goal"]: raise SyncError("conflicting_goal")
                if old["status"] == "ready":
                    try:
                        latest = self._state(session)
                        if self.clock() - old["readyAt"] > 10 or latest["companion"] is None or latest["companion"]["id"] != old["companion"] or latest["dimension"] != old["dimension"]:
                            raise SyncError("expired")
                        validate_decision({k: old["action"][k] for k in ("decision", "reasonCode")},
                                          sanitize(self._input(goal, latest)))
                    except (SyncError, DecisionError, ValueError):
                        old["status"], old["error"] = "failed", "expired_or_unsafe"
                        self._remember(session, old, "failed", "expired")
                return self._view(session, old)
            # Cancellation must work even if observations have become stale or disappeared.
            state = self._state(session) if goal is not None else None
            if goal is not None:
                if self.decisions is None: raise DecisionError("decision_not_configured")
                sanitize(self._input(goal, state))
            if old and old.get("action") and old["status"] not in TERMINAL:
                self._remember(session, old, "cancelled", "replaced")
            entry = {"revision": revision, "goal": goal, "status": "thinking" if goal else "idle",
                     "action": None, "error": None,
                     "companion": state["companion"]["id"] if state else None,
                     "dimension": state["dimension"] if state else None}
            self.entries[session] = entry
            self.entries.move_to_end(session)
            self.pending.pop(session, None)
            while len(self.entries) > 32:
                evicted, _ = self.entries.popitem(last=False)
                self.pending.pop(evicted, None)
            if goal:
                self.pending[session] = entry
                if self.worker is None:
                    self.worker = threading.Thread(target=self._run, daemon=True, name="mcai-decisions")
                    self.worker.start()
            return self._view(session, entry)

    def _view(self, session, entry):
        return {"version": 1, "session": session, "goalRevision": entry["revision"],
                "status": entry["status"], "error": entry["error"],
                "action": copy.deepcopy(entry["action"]) if entry["status"] == "ready" else None}

    def _run(self):
        while True:
            with self.lock:
                if not self.pending:
                    self.worker = None
                    return
                session, entry = self.pending.popitem(last=False)
            try:
                state = self._state(session)
                decision = self.decisions.decide(self._input(entry["goal"], state))
                with self.lock:
                    if self.entries.get(session) is not entry: continue
                    latest = self._state(session)
                    if latest["companion"] is None or latest["companion"]["id"] != entry["companion"] or latest["dimension"] != entry["dimension"]:
                        raise SyncError("companion_changed")
                    validated = validate_decision({k: decision[k] for k in ("decision", "reasonCode")},
                                                  sanitize(self._input(entry["goal"], latest)))
                    entry["action"] = {"actionId": str(uuid.uuid4()), "companionId": entry["companion"],
                                       "dimension": entry["dimension"], **validated}
                    entry["status"], entry["readyAt"] = "ready", self.clock()
                    LOG.info("action ready revision=%d kind=%s", entry["revision"], validated["decision"]["action"])
            except BudgetExceeded as exc:
                LOG.warning("goal context budget rejected code=%s", str(exc))
                with self.lock:
                    if self.entries.get(session) is entry:
                        entry["status"], entry["error"] = "failed", str(exc)
            except (DecisionError, SyncError, ValueError):
                with self.lock:
                    if self.entries.get(session) is entry:
                        entry["status"], entry["error"] = "failed", "decision_failed"
            except Exception as exc:
                # Unexpected provider errors must not strand the single worker or expose its text.
                LOG.error("goal worker failure type=%s", type(exc).__name__)
                with self.lock:
                    if self.entries.get(session) is entry:
                        entry["status"], entry["error"] = "failed", "internal_failure"

    def _remember(self, session, entry, status, reason):
        LOG.info("action result revision=%d status=%s reason=%s", entry["revision"], status, reason)
        key = (session, entry["revision"], entry["action"]["actionId"])
        self.results[key] = (status, reason)
        while len(self.results) > 100: self.results.popitem(last=False)

    def result(self, data):
        envelope(data, {"actionId", "status", "reason"})
        identity(data["actionId"])
        status, reason = data["status"], data["reason"]
        if type(status) is not str or status not in TERMINAL | {"running"} or type(reason) is not str or reason not in REASONS:
            raise ValueError("invalid_action_result")
        key = (data["session"], data["goalRevision"], data["actionId"])
        with self.lock:
            entry = self.entries.get(data["session"])
            matches = entry and entry["revision"] == data["goalRevision"] and entry.get("action") and entry["action"]["actionId"] == data["actionId"]
            if not matches:
                if key not in self.results: raise SyncError("unknown_action")
                # A late result is acknowledged, but cannot alter the replacement goal.
            elif entry["status"] not in TERMINAL:
                if entry["status"] not in ("ready", "running"): raise SyncError("action_not_issued")
                entry["status"] = status
                self._remember(data["session"], entry, status, reason)
            return {"version": 1, "accepted": True}
