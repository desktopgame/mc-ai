"""Bounded collect_drop Skill lifecycle. No Minecraft, HTTP, or provider dependencies.

The manager owns Skill progress and target selection. The Forge side remains the authority
for the real world: the Daemon's candidates are never an arrival guarantee.
"""
import copy
import logging
import threading
import time
import uuid
from collections import OrderedDict

from skill_protocol import (CAPABILITIES, FAILURE_REASONS, PATH_FAILURES, SkillBusyError,
                            SkillSyncError, parse_collect_drop, validate_goal, validate_open,
                            validate_result, validate_status)

LOG = logging.getLogger("mcai.skills")

SKILL_DEADLINE = 120.0
ACTION_TIMEOUT = 30.0
READY_TIMEOUT = 10.0
RUNNING_TIMEOUT = 40.0
SEARCH_WINDOW = 6.0
CANCEL_GRACE = 5.0
CONTROL_LEASE = 5.0
MAX_FAILURES = 3
MAX_ACTIONS = 192
MAX_ACTIVE = 32
MAX_TERMINAL = 100
TERMINAL_TTL = 600.0


class CollectDrop:
    def __init__(self, session, revision, goal, skill_id, now, companion, dimension):
        self.session = session
        self.revision = revision
        self.goal = goal
        self.skill_id = skill_id
        self.item = goal["target"]["item"]
        self.requested = goal["count"]
        self.companion = companion
        self.dimension = dimension
        self.acquired = 0
        self.phase = "selecting"
        self.issued = 0
        self.failures = 0
        self.saw_path_failure = False
        self.excluded = set()
        self.current = None
        self.result = None
        self.started = now
        self.deadline = now + SKILL_DEADLINE
        self.search_started = None
        self.fence_sequence = None
        self.fence_until = None
        self.cancelling_since = None
        self.cancel_reason = None
        self.finished_at = None
        self.settled = {}          # actionId -> {"status", "reason", "count"}
        self.unresolved = False

    def definition(self):
        return {"type": "collect_drop", "target": {"item": self.item}, "count": self.requested, "constraints": []}

    def progress(self):
        return {"requested": self.requested, "acquired": self.acquired, "complete": not self.unresolved}

    def view(self):
        return {"skillInstanceId": self.skill_id, "type": "collect_drop",
                "target": {"item": self.item}, "phase": self.phase,
                "progress": self.progress(), "result": copy.deepcopy(self.result)}


class SkillManager:
    def __init__(self, states, registry, clock=time.monotonic, goals=None):
        self.states, self.registry, self.clock, self.goals = states, registry, clock, goals
        self.lock = threading.Lock()
        self.active = OrderedDict()   # session -> CollectDrop
        self.other = OrderedDict()    # skillInstanceId -> CollectDrop (settling or terminal)
        self.revisions = {}           # session -> accepted revision
        self.leases = {}              # session -> last verified control poll time

    # ---- ingestion -------------------------------------------------------
    def open(self, data):
        parsed = validate_open(data)
        session = parsed["session"]
        state = self._fresh_state(session)
        companion = state["companion"]
        if companion is None:
            raise SkillSyncError("companion_unavailable")
        invalidated = self.registry.open(session, companion["id"], state["dimension"])
        with self.lock:
            for old in invalidated:
                self._cancel_session(old, "disconnected")
        return {"version": 2, "session": session, "daemonEpoch": self.registry.epoch,
                "capabilities": list(CAPABILITIES)}

    def update(self, data):
        parsed = validate_goal(data)
        if parsed["daemonEpoch"] != self.registry.epoch:
            raise SkillSyncError("daemon_restarted")
        session = parsed["session"]
        if not self.registry.is_open(session):
            raise SkillSyncError("not_opened")
        goal = parsed["goal"]
        revision = parsed["goalRevision"]
        if isinstance(goal, str):
            return self._legacy(session, revision, goal)
        with self.lock:
            # Only an accepted control poll renews the lease; action results and status reads do not.
            self.leases[session] = self.clock()
            previous = self.revisions.get(session)
            if previous is not None and revision < previous:
                raise SkillSyncError("stale_goal")
            if previous is not None and revision == previous:
                return self._poll_locked(session, goal)
            if goal is None:
                self._cancel_session(session, "stopped")
                self.revisions[session] = revision
                return self._empty_view(session, revision)
            state = self._fresh_state(session)
            companion = state["companion"]
            if companion is None:
                raise SkillSyncError("companion_unavailable")
            if len(self.active) >= MAX_ACTIVE and session not in self.active:
                raise SkillBusyError()
            old = self.active.get(session)
            if old is not None:
                self._move_to_settling(old, "replaced")
            skill = CollectDrop(session, revision, goal, str(uuid.uuid4()), self.clock(),
                                companion["id"], state["dimension"])
            self.active[session] = skill
            self.revisions[session] = revision
            self._step(skill)
            return self._view(session, skill)

    def result(self, data):
        parsed = validate_result(data)
        if parsed["kind"] != "skill":
            raise SkillSyncError("unknown_skill")
        if parsed["daemonEpoch"] != self.registry.epoch:
            raise SkillSyncError("daemon_restarted")
        session = parsed["session"]
        with self.lock:
            skill = self._find(session, parsed["skillInstanceId"])
            if skill is None:
                raise SkillSyncError("unknown_skill")
            self._apply_result(skill, parsed)
        return {"version": 2, "accepted": True}

    def status(self, data):
        parsed = validate_status(data)
        if parsed["daemonEpoch"] != self.registry.epoch:
            raise SkillSyncError("daemon_restarted")
        with self.lock:
            skill = self._find(parsed["session"], parsed["skillInstanceId"])
            if skill is None:
                raise SkillSyncError("unknown_skill")
            view = self._view(parsed["session"], skill)
        view["action"] = None
        return view

    def tick(self):
        with self.lock:
            for skill in list(self.active.values()):
                self._advance(skill)
            for skill in list(self.other.values()):
                if skill.phase == "cancelling":
                    self._advance_cancelling(skill)
            self._expire_terminal()

    # ---- internal --------------------------------------------------------
    def _fresh_state(self, session):
        cached = self.states.view({"version": 1, "session": session})
        if cached["stale"]:
            raise SkillSyncError("stale_state")
        return cached["state"]

    def _cache(self, session):
        cached = self.states.view({"version": 1, "session": session})
        return cached

    def _legacy(self, session, revision, goal):
        if self.goals is None:
            raise SkillSyncError("unsupported_goal")
        view = self.goals.update({"version": 1, "session": session, "goalRevision": revision, "goal": goal})
        return self._legacy_view(view)

    def _legacy_view(self, view):
        status = {"ready": "running", "succeeded": "completed", "thinking": "thinking"}.get(view["status"], view["status"])
        action = None
        if view.get("action"):
            action = {"type": "legacy_action", "payload": view["action"]}
        return {"version": 2, "session": view["session"], "daemonEpoch": self.registry.epoch,
                "goalRevision": view["goalRevision"], "status": status, "error": view["error"],
                "skill": None, "action": action}

    def _poll_locked(self, session, goal):
        skill = self.active.get(session)
        if skill is not None:
            if skill.definition() != goal:
                raise SkillSyncError("conflicting_goal")
            self._step(skill)
            return self._view(session, skill)
        for value in self.other.values():
            if value.session == session and value.revision == self.revisions.get(session):
                if value.goal != goal:
                    raise SkillSyncError("conflicting_goal")
                return self._view(session, value)
        if goal is not None:
            raise SkillSyncError("conflicting_goal")
        return self._empty_view(session, self.revisions[session])

    def _empty_view(self, session, revision):
        return {"version": 2, "session": session, "daemonEpoch": self.registry.epoch,
                "goalRevision": revision, "status": "idle", "error": None, "skill": None, "action": None}

    def _view(self, session, skill):
        status = skill.result["status"] if skill.result else "running"
        action = None
        if skill.phase == "waiting_action" and skill.current is not None and not skill.current["running"]:
            current = skill.current
            action = {"type": "pickup_target", "actionId": current["actionId"],
                      "actionSequence": current["sequence"], "skillInstanceId": skill.skill_id,
                      "companionId": skill.companion, "dimension": skill.dimension,
                      "targetRef": current["targetRef"], "item": skill.item,
                      "maxCount": current["maxCount"], "timeoutMs": int(ACTION_TIMEOUT * 1000),
                      "observationSequence": current["observationSequence"]}
        return {"version": 2, "session": session, "daemonEpoch": self.registry.epoch,
                "goalRevision": skill.revision, "status": status, "error": None,
                "skill": skill.view(), "action": action}

    def _find(self, session, skill_id):
        skill = self.active.get(session)
        if skill is not None and skill.skill_id == skill_id:
            return skill
        value = self.other.get(skill_id)
        if value is not None and value.session == session:
            return value
        return None

    def _cancel_session(self, session, reason):
        skill = self.active.pop(session, None)
        if skill is None:
            return
        skill.cancel_reason = reason
        if skill.current is None or not skill.unresolved:
            self._finalize(skill, "cancelled", reason)
        else:
            skill.phase = "cancelling"
            skill.cancelling_since = self.clock()
            self.other[skill.skill_id] = skill

    def _move_to_settling(self, skill, reason):
        self.active.pop(skill.session, None)
        skill.cancel_reason = reason
        if skill.current is None or not skill.unresolved:
            self._finalize(skill, "cancelled", reason)
        else:
            skill.phase = "cancelling"
            skill.cancelling_since = self.clock()
            self.other[skill.skill_id] = skill

    def _finalize(self, skill, status, reason):
        skill.phase = "terminal"
        skill.finished_at = self.clock()
        skill.result = {"skillInstanceId": skill.skill_id, "status": status, "reason": reason,
                        "progress": skill.progress()}
        if self.active.get(skill.session) is skill:
            del self.active[skill.session]
        self.other[skill.skill_id] = skill
        self.other.move_to_end(skill.skill_id)
        LOG.info("skill terminal session=%s status=%s reason=%s acquired=%d/%d",
                 skill.session, status, reason, skill.acquired, skill.requested)
        self._expire_terminal()

    def _expire_terminal(self):
        now = self.clock()
        for skill_id in list(self.other.keys()):
            skill = self.other[skill_id]
            if skill.phase == "terminal" and now - skill.finished_at > TERMINAL_TTL:
                del self.other[skill_id]
        terminal_ids = [skill_id for skill_id, skill in self.other.items() if skill.phase == "terminal"]
        while len(self.other) > MAX_TERMINAL and terminal_ids:
            del self.other[terminal_ids.pop(0)]

    def _advance(self, skill):
        if skill.phase == "terminal":
            return
        now = self.clock()
        if skill.phase == "cancelling":
            self._advance_cancelling(skill)
            return
        if now > skill.deadline:
            self._finalize(skill, "failed", "expired")
            return
        if skill.phase == "waiting_action":
            current = skill.current
            if current is not None:
                limit = RUNNING_TIMEOUT if current["running"] else READY_TIMEOUT
                if now - current["issuedAt"] > limit:
                    self._finalize(skill, "failed", "expired")
            return
        if skill.phase == "selecting":
            self._step(skill)

    def _advance_cancelling(self, skill):
        if skill.current is None or not skill.unresolved:
            self._finalize(skill, "cancelled", skill.cancel_reason or "replaced")
        elif self.clock() - skill.cancelling_since > CANCEL_GRACE:
            self._finalize(skill, "cancelled", skill.cancel_reason or "replaced")

    def _step(self, skill):
        if skill.phase != "selecting":
            return
        if skill.acquired >= skill.requested and not skill.unresolved:
            self._finalize(skill, "completed", "completed")
            return
        if skill.current is not None:
            return
        now = self.clock()
        # No fresh control lease: do not issue. The Forge renews it about once a second while it runs.
        if now - self.leases.get(skill.session, float("-inf")) > CONTROL_LEASE:
            return
        cached = self._cache(skill.session)
        if skill.fence_sequence is not None:
            if not cached["stale"] and cached["sequence"] > skill.fence_sequence:
                skill.fence_sequence = None
                skill.fence_until = None
            elif now < (skill.fence_until or now):
                return
            else:
                skill.fence_sequence = None
                skill.fence_until = None
        if cached["stale"]:
            self._searching(skill, now, fresh=False)
            return
        state = cached["state"]
        candidates = sorted((value["distance"], key) for key, value in state["items"].items()
                            if value["type"] == skill.item and key not in skill.excluded)
        if not candidates:
            self._searching(skill, now, fresh=True)
            return
        skill.search_started = None
        if skill.issued >= MAX_ACTIONS:
            self._finalize(skill, "failed", "retry_exhausted")
            return
        _, target_ref = candidates[0]
        skill.issued += 1
        skill.current = {"actionId": str(uuid.uuid4()), "sequence": skill.issued, "targetRef": target_ref,
                         "maxCount": min(skill.requested - skill.acquired, 64),
                         "observationSequence": cached["sequence"], "issuedAt": now, "running": False}
        skill.unresolved = True
        skill.phase = "waiting_action"

    def _searching(self, skill, now, fresh):
        if skill.search_started is None:
            skill.search_started = now
            return
        if now - skill.search_started <= SEARCH_WINDOW:
            return
        if not fresh:
            self._finalize(skill, "failed", "stale_state")
        else:
            self._finalize(skill, "failed", "path_not_found" if skill.saw_path_failure else "no_item_in_range")

    def _apply_result(self, skill, parsed):
        if skill.phase == "terminal":
            recorded = skill.settled.get(parsed["actionId"])
            if recorded is None:
                raise SkillSyncError("unknown_action")
            if recorded["status"] != parsed["status"] or recorded["reason"] != parsed["reason"] \
                    or recorded["count"] != parsed["acquired"]["count"]:
                raise SkillSyncError("conflicting_result")
            return
        current = skill.current
        if current is None or current["actionId"] != parsed["actionId"] \
                or current["sequence"] != parsed["actionSequence"]:
            if parsed["actionId"] in skill.settled:
                recorded = skill.settled[parsed["actionId"]]
                if recorded["status"] == parsed["status"] and recorded["count"] == parsed["acquired"]["count"]:
                    return
                raise SkillSyncError("conflicting_result")
            raise SkillSyncError("unknown_action")
        if parsed["acquired"]["item"] != skill.item:
            raise SkillSyncError("conflicting_result")
        if parsed["status"] == "running":
            current["running"] = True
            return
        count = parsed["acquired"]["count"]
        if parsed["status"] == "succeeded" and count > current["maxCount"]:
            raise SkillSyncError("conflicting_result")
        target_ref = current["targetRef"]
        issued_sequence = current["observationSequence"]
        skill.settled[parsed["actionId"]] = {"status": parsed["status"], "reason": parsed["reason"], "count": count}
        skill.unresolved = False
        skill.current = None
        if parsed["status"] == "succeeded":
            skill.acquired += count
            skill.failures = 0
            skill.fence_sequence = issued_sequence
            skill.fence_until = self.clock() + SEARCH_WINDOW
            if skill.acquired >= skill.requested:
                self._finalize(skill, "completed", "completed")
            else:
                skill.phase = "selecting"
                self._step(skill)
            return
        if parsed["status"] == "cancelled":
            # Forge only cancels an action when it intentionally stopped it, so this is terminal.
            if skill.phase == "cancelling":
                self._finalize(skill, "cancelled", skill.cancel_reason or "replaced")
            else:
                reason = parsed["reason"] if parsed["reason"] in ("stopped", "replaced", "disconnected") else "replaced"
                self._finalize(skill, "cancelled", reason)
            return
        # failed
        if skill.phase == "cancelling":
            skill.excluded.add(target_ref)
            self._finalize(skill, "cancelled", skill.cancel_reason or "replaced")
            return
        skill.failures += 1
        if parsed["reason"] in PATH_FAILURES:
            skill.saw_path_failure = True
        if parsed["reason"] in FAILURE_REASONS:
            skill.excluded.add(target_ref)
        if skill.failures >= MAX_FAILURES:
            self._finalize(skill, "failed", "retry_exhausted")
        else:
            skill.phase = "selecting"
            self._step(skill)
