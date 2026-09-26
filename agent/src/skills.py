"""Bounded Skill lifecycle (collect_drop / mine / collect_block). No Minecraft, HTTP, or provider deps.

The manager owns Skill progress, stage transitions and target selection. The Forge side remains the
authority for the real world: the Daemon's candidates are never an arrival guarantee. Settlement is
driven by the immutable descriptor of each issued action, so a Skill can alternate different action
types (mine_target / pickup_target) inside one instance.
"""
import copy
import logging
import threading
import time
import uuid
from collections import OrderedDict

from skill_protocol import (CAPABILITIES, COLLECT_BLOCK_TARGETS, FAILURE_REASONS, PATH_FAILURES,
                            TERMINAL_FAILURE_REASONS, SkillBusyError, SkillSyncError, validate_goal,
                            validate_open, validate_result, validate_status)

LOG = logging.getLogger("mcai.skills")

SKILL_DEADLINE = 120.0
ACTION_TIMEOUT = 30.0
READY_TIMEOUT = 10.0
RUNNING_TIMEOUT = 40.0
SEARCH_WINDOW = 6.0
DROP_WINDOW = 6.0
CANCEL_GRACE = 5.0
CONTROL_LEASE = 5.0
MAX_FAILURES = 3
MAX_ACTIONS = 192
MAX_ACTIVE = 32
MAX_TERMINAL = 100
TERMINAL_TTL = 600.0


class Skill:
    """Shared lifecycle: phases, timers, fencing, retry and descriptor-based settlement."""
    kind = ""
    target_field = ""       # goal/view target field: "item" | "block"
    not_found_reason = ""

    def __init__(self, session, revision, goal, skill_id, now, companion, dimension):
        self.session = session
        self.revision = revision
        self.goal = goal
        self.skill_id = skill_id
        self.requested = goal["count"]
        self.companion = companion
        self.dimension = dimension
        self.acquired = 0
        self.mined = 0
        self.phase = "selecting"
        self.issued = 0
        self.failures = 0
        self.saw_path_failure = False
        self.saw_blocked = False
        self.excluded = set()             # cache keys skipped by simple Skills
        self.current = None               # descriptor of the outstanding action
        self.result = None
        self.started = now
        self.deadline = now + SKILL_DEADLINE
        self.search_started = None
        self.fence_sequence = None
        self.fence_until = None
        self.cancelling_since = None
        self.cancel_reason = None
        self.finished_at = None
        self.settled = {}                 # actionId -> {"status", "reason", "count"}
        self.issued_ids = set()           # every actionId this Skill ever issued
        self.descriptors = {}             # actionId -> immutable issued-action descriptor
        self.unresolved = False

    # ---- per-kind hooks --------------------------------------------------
    def target_id(self):
        raise NotImplementedError

    def target_json(self):
        return {self.target_field: self.target_id()}

    def achieved(self):
        """The progress value that the requested count is measured against."""
        raise NotImplementedError

    def progress_fields(self):
        raise NotImplementedError

    def candidate_entries(self, state):
        """[(distance, cache_key)] for the simple single-action Skills; overridden per kind."""
        raise NotImplementedError

    def primary_action(self):
        """(action_type, payloadField, name, maxCount) for the simple single-action Skills."""
        raise NotImplementedError

    # ---- extensibility hooks (CollectBlock overrides) --------------------
    def exclude(self, cache_key, field):
        self.excluded.add(cache_key)

    def on_action_succeeded(self, descriptor, count, now):
        pass

    def on_action_failed(self, descriptor, reason, now):
        pass

    # ---- shared views ----------------------------------------------------
    def definition(self):
        return {"type": self.kind, "target": self.target_json(), "count": self.requested, "constraints": []}

    def progress(self):
        data = {"requested": self.requested}
        data.update(self.progress_fields())
        data["complete"] = not self.unresolved
        return data

    def view(self):
        return {"skillInstanceId": self.skill_id, "type": self.kind, "target": self.target_json(),
                "phase": self.phase, "progress": self.progress(), "result": copy.deepcopy(self.result)}

    def action_json(self, current):
        action = {"type": current["actionType"], "actionId": current["actionId"],
                  "actionSequence": current["sequence"], "skillInstanceId": self.skill_id,
                  "companionId": self.companion, "dimension": self.dimension,
                  "targetRef": current["targetRef"], current["payloadField"]: current["name"],
                  "timeoutMs": int(ACTION_TIMEOUT * 1000), "observationSequence": current["observationSequence"]}
        if current["actionType"] == "pickup_target":
            action["maxCount"] = current["maxCount"]
        return action


class CollectDrop(Skill):
    kind = "collect_drop"
    target_field = "item"
    not_found_reason = "no_item_in_range"

    def __init__(self, session, revision, goal, skill_id, now, companion, dimension):
        super().__init__(session, revision, goal, skill_id, now, companion, dimension)
        self.item = goal["target"]["item"]

    def target_id(self):
        return self.item

    def achieved(self):
        return self.acquired

    def progress_fields(self):
        return {"acquired": self.acquired}

    def candidate_entries(self, state):
        return [(value["distance"], key) for key, value in state["items"].items()
                if value["type"] == self.item and key not in self.excluded]

    def primary_action(self):
        return "pickup_target", "item", self.item, min(self.requested - self.acquired, 64)


class Mine(Skill):
    kind = "mine"
    target_field = "block"
    not_found_reason = "no_block_in_range"

    def __init__(self, session, revision, goal, skill_id, now, companion, dimension):
        super().__init__(session, revision, goal, skill_id, now, companion, dimension)
        self.block = goal["target"]["block"]

    def target_id(self):
        return self.block

    def achieved(self):
        return self.mined

    def progress_fields(self):
        return {"mined": self.mined}

    def candidate_entries(self, state):
        return [(value["distance"], key) for key, value in state["blocks"].items()
                if value["type"] == self.block and key not in self.excluded]

    def primary_action(self):
        return "mine_target", "block", self.block, 1


class CollectBlock(Skill):
    """collect_block(block, count): order mine_target and pickup_target until count items are stored.

    Success is measured by `acquired` (items this Skill actually stored), never by `mined`. Block
    destruction is a separate side-effect counter. Recovery never mines again until a pickup succeeds.
    """
    kind = "collect_block"
    target_field = "block"
    not_found_reason = "no_block_in_range"

    def __init__(self, session, revision, goal, skill_id, now, companion, dimension):
        super().__init__(session, revision, goal, skill_id, now, companion, dimension)
        mapping = COLLECT_BLOCK_TARGETS[goal["target"]["block"]]
        self.block_target = mapping["block"]
        self.item_target = mapping["item"]
        self.stage = "select_source"      # select_source / wait_drop / recover_drop
        self.mined_blocks = set()         # block cache keys already mined by this Skill
        self.excluded_blocks = set()
        self.excluded_items = set()
        self.wait_started = None
        self.wait_deadline = None         # absolute: mine terminal receipt + DROP_WINDOW
        self.wait_sequence = None
        self.recover_deadline = None      # absolute: first recovery pickup failure + DROP_WINDOW

    def target_id(self):
        return self.block_target

    def achieved(self):
        return self.acquired

    def progress_fields(self):
        return {"acquired": self.acquired, "mined": self.mined}

    def exclude(self, cache_key, field):
        (self.excluded_items if field == "item" else self.excluded_blocks).add(cache_key)

    def on_action_succeeded(self, descriptor, count, now):
        if descriptor["payloadField"] == "block":
            self.mined_blocks.add(descriptor["candidateKey"])
            self.stage = "wait_drop"
            self.wait_started = now
            self.wait_deadline = now + DROP_WINDOW
            self.wait_sequence = descriptor["observationSequence"]
        else:
            self.stage = "select_source"
            self.recover_deadline = None

    def on_action_failed(self, descriptor, reason, now):
        if descriptor["payloadField"] == "block":
            self.stage = "select_source"
        else:
            if self.recover_deadline is None:
                self.recover_deadline = now + DROP_WINDOW
            self.stage = "recover_drop"

    def _item_candidates(self, state):
        return [(value["distance"], key) for key, value in state["items"].items()
                if value["type"] == self.item_target and key not in self.excluded_items]

    def _block_candidates(self, state):
        return [(value["distance"], key) for key, value in state["blocks"].items()
                if value["type"] == self.block_target and key not in self.excluded_blocks
                and key not in self.mined_blocks]


SKILL_TYPES = {"collect_drop": CollectDrop, "mine": Mine, "collect_block": CollectBlock}


class SkillManager:
    def __init__(self, states, registry, clock=time.monotonic, terminal_store=None):
        self.states, self.registry, self.clock = states, registry, clock
        self.terminal_store = terminal_store
        self.lock = threading.Lock()
        self.active = OrderedDict()   # session -> Skill
        self.other = OrderedDict()    # skillInstanceId -> Skill (settling or terminal)
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
        with self.lock:
            now = self.clock()
            previous = self.revisions.get(session)
            if previous is not None and revision < previous:
                raise SkillSyncError("stale_goal")
            if goal is None:
                # goal:null is an idempotent Skill cancellation: it must be accepted at the skill's
                # own revision too, so a lost/retried cancel ACK still resolves to idle.
                self._cancel_session(session, "stopped")
                if previous is None or revision > previous:
                    self.revisions[session] = revision
                self.leases[session] = now
                return self._empty_view(session, self.revisions[session])
            if previous is not None and revision == previous:
                # Rejected polls (conflicting/stale) must not renew the lease.
                return self._poll_locked(session, goal)
            state = self._fresh_state(session)
            companion = state["companion"]
            if companion is None:
                raise SkillSyncError("companion_unavailable")
            if len(self.active) >= MAX_ACTIVE and session not in self.active:
                raise SkillBusyError()
            old = self.active.get(session)
            if old is not None:
                self._move_to_settling(old, "replaced")
            factory = SKILL_TYPES.get(goal["type"])
            if factory is None:
                raise SkillSyncError("unsupported_goal")
            skill = factory(session, revision, goal, str(uuid.uuid4()), now,
                            companion["id"], state["dimension"])
            self.active[session] = skill
            self.revisions[session] = revision
            self.leases[session] = now
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
            if parsed["goalRevision"] != skill.revision:
                raise SkillSyncError("unknown_action")
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
        return self.states.view({"version": 1, "session": session})

    def _poll_locked(self, session, goal):
        skill = self.active.get(session)
        if skill is not None:
            if skill.definition() != goal:
                raise SkillSyncError("conflicting_goal")
            self.leases[session] = self.clock()
            self._step(skill)
            return self._view(session, skill)
        for value in self.other.values():
            if value.session == session and value.revision == self.revisions.get(session):
                if value.goal != goal:
                    raise SkillSyncError("conflicting_goal")
                self.leases[session] = self.clock()
                return self._view(session, value)
        if goal is not None:
            raise SkillSyncError("conflicting_goal")
        self.leases[session] = self.clock()
        return self._empty_view(session, self.revisions[session])

    def _empty_view(self, session, revision):
        return {"version": 2, "session": session, "daemonEpoch": self.registry.epoch,
                "goalRevision": revision, "status": "idle", "error": None, "skill": None, "action": None}

    def _view(self, session, skill):
        status = skill.result["status"] if skill.result else "running"
        action = None
        if skill.phase == "waiting_action" and skill.current is not None and not skill.current["running"]:
            action = skill.action_json(skill.current)
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
        if skill.phase == "terminal":
            return
        skill.phase = "terminal"
        skill.finished_at = self.clock()
        skill.result = {"skillInstanceId": skill.skill_id, "status": status, "reason": reason,
                        "progress": skill.progress()}
        if self.active.get(skill.session) is skill:
            del self.active[skill.session]
        self.other[skill.skill_id] = skill
        self.other.move_to_end(skill.skill_id)
        LOG.info("skill terminal session=%s kind=%s status=%s reason=%s acquired=%d mined=%d requested=%d",
                 skill.session, skill.kind, status, reason, skill.acquired, skill.mined, skill.requested)
        if self.terminal_store is not None:
            # The terminal result is the authority: only its already-finalized values are recorded,
            # once, without calling back into the Skill or any provider while holding the lock.
            try:
                self.terminal_store.record({
                    "version": 2, "category": "skill_terminal", "terminalId": str(uuid.uuid4()),
                    "daemonEpoch": self.registry.epoch, "session": skill.session,
                    "goalRevision": skill.revision, "skillInstanceId": skill.skill_id,
                    "type": skill.kind, "status": status, "reason": reason,
                    "target": skill.target_json(), "progress": copy.deepcopy(skill.result["progress"])})
            except Exception:  # A store failure must never strand the Skill or change its result.
                LOG.exception("terminal event record failed skill=%s", skill.skill_id)
        self._expire_terminal()

    def _close_if_settled(self, skill):
        """Single first-wins rule after a receipt has been settled exactly once.

        A cancel/replace that was already established closes the Skill as cancelled (even when the
        requested count was reached), and a reached requested count completes it. A cancelled Skill
        never issues a new action and never falls back into selecting.
        """
        if skill.phase == "cancelling":
            self._finalize(skill, "cancelled", skill.cancel_reason or "replaced")
            return True
        if skill.achieved() >= skill.requested:
            self._finalize(skill, "completed", "completed")
            return True
        return False

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

    # ---- selection -------------------------------------------------------
    def _fence_ready(self, skill, cached, now):
        if skill.fence_sequence is not None:
            if not cached["stale"] and cached["sequence"] > skill.fence_sequence:
                skill.fence_sequence = None
                skill.fence_until = None
            elif now < (skill.fence_until or now):
                return False
            else:
                skill.fence_sequence = None
                skill.fence_until = None
        return True

    def _lease_ok(self, skill, now):
        return now - self.leases.get(skill.session, float("-inf")) <= CONTROL_LEASE

    def _issue(self, skill, cache_key, name, action_type, payload_field, max_count, cached_sequence, now):
        skill.issued += 1
        action_id = str(uuid.uuid4())
        descriptor = {"actionId": action_id, "sequence": skill.issued, "targetRef": cache_key,
                      "candidateKey": cache_key, "name": name, "actionType": action_type,
                      "payloadField": payload_field, "maxCount": max_count,
                      "observationSequence": cached_sequence, "issuedAt": now, "running": False}
        skill.current = descriptor
        skill.descriptors[action_id] = descriptor
        skill.issued_ids.add(action_id)
        skill.unresolved = True
        skill.phase = "waiting_action"

    def _try_issue(self, skill, cache_key, cached_sequence, now, action):
        skill.search_started = None
        if skill.issued >= MAX_ACTIONS:
            self._finalize(skill, "failed", "retry_exhausted")
            return
        # No fresh control lease: do not issue. The Forge renews it about once a second while it runs.
        if not self._lease_ok(skill, now):
            return
        action_type, payload_field, name, max_count = action
        self._issue(skill, cache_key, name, action_type, payload_field, max_count, cached_sequence, now)

    def _step(self, skill):
        if isinstance(skill, CollectBlock):
            self._step_collect(skill)
        else:
            self._step_simple(skill)

    def _step_simple(self, skill):
        if skill.phase != "selecting":
            return
        if skill.achieved() >= skill.requested and not skill.unresolved:
            self._finalize(skill, "completed", "completed")
            return
        if skill.current is not None:
            return
        now = self.clock()
        cached = self._cache(skill.session)
        if not self._fence_ready(skill, cached, now):
            return
        if cached["stale"]:
            self._searching(skill, now, fresh=False)
            return
        candidates = sorted(skill.candidate_entries(cached["state"]))
        if not candidates:
            self._searching(skill, now, fresh=True)
            return
        _, cache_key = candidates[0]
        self._try_issue(skill, cache_key, cached["sequence"], now, skill.primary_action())

    def _searching(self, skill, now, fresh):
        if skill.saw_blocked:
            # A candidate was observed but obstructed and no executable candidate remains: report it
            # immediately instead of waiting out the search window.
            self._finalize(skill, "failed", "blocked")
            return
        if skill.search_started is None:
            skill.search_started = now
            return
        if now - skill.search_started <= SEARCH_WINDOW:
            return
        if not fresh:
            self._finalize(skill, "failed", "stale_state")
        else:
            self._finalize(skill, "failed", "path_not_found" if skill.saw_path_failure else skill.not_found_reason)

    # ---- collect_block stage machine ------------------------------------
    def _step_collect(self, skill):
        if skill.phase != "selecting":
            return
        if skill.achieved() >= skill.requested and not skill.unresolved:
            self._finalize(skill, "completed", "completed")
            return
        if skill.current is not None:
            return
        now = self.clock()
        cached = self._cache(skill.session)
        # wait_drop / recover_drop use their own absolute stage deadline; the generic freshness fence
        # must not silently add another SEARCH_WINDOW on top of it.
        if skill.stage == "wait_drop":
            self._wait_drop(skill, cached, now)
            return
        if skill.stage == "recover_drop":
            self._recover_drop(skill, cached, now)
            return
        if not self._fence_ready(skill, cached, now):
            return
        if cached["stale"]:
            self._searching_collect(skill, now, fresh=False)
            return
        state = cached["state"]
        items = sorted(skill._item_candidates(state))
        if items:
            self._try_issue(skill, items[0][1], cached["sequence"], now,
                            ("pickup_target", "item", skill.item_target, min(skill.requested - skill.acquired, 64)))
            return
        if skill.mined < skill.requested:
            blocks = sorted(skill._block_candidates(state))
            if blocks:
                self._try_issue(skill, blocks[0][1], cached["sequence"], now,
                                ("mine_target", "block", skill.block_target, 1))
                return
        self._searching_collect(skill, now, fresh=True)

    def _wait_drop(self, skill, cached, now):
        # A newer, non-stale observation that arrived after the mine is the only thing that can move
        # this stage; the deadline itself is fixed at the mine receipt and polls never extend it.
        newer = (not cached["stale"]) and cached["sequence"] > (skill.wait_sequence if skill.wait_sequence is not None else -1)
        if newer:
            items = sorted(skill._item_candidates(cached["state"]))
            if items:
                skill.stage = "recover_drop"
                skill.recover_deadline = None
                self._try_issue(skill, items[0][1], cached["sequence"], now,
                                ("pickup_target", "item", skill.item_target, min(skill.requested - skill.acquired, 64)))
                return
        if skill.wait_deadline is not None and now >= skill.wait_deadline:
            # A fresh newer observation without the item is a real "nothing dropped" result; with no
            # newer observation at all we only know the cache stopped updating.
            self._finalize(skill, "failed", "drop_unavailable" if newer else "stale_state")

    def _recover_drop(self, skill, cached, now):
        # Recovery never mines again until a pickup succeeds; the deadline is fixed at the first
        # recovery failure and is not extended by candidate churn.
        items = [] if cached["stale"] else sorted(skill._item_candidates(cached["state"]))
        if items:
            self._try_issue(skill, items[0][1], cached["sequence"], now,
                            ("pickup_target", "item", skill.item_target, min(skill.requested - skill.acquired, 64)))
            return
        if skill.recover_deadline is None:
            skill.recover_deadline = now + DROP_WINDOW
        if now >= skill.recover_deadline:
            self._finalize(skill, "failed",
                           "path_not_found" if skill.saw_path_failure else "drop_unavailable")

    def _searching_collect(self, skill, now, fresh):
        if skill.saw_blocked:
            self._finalize(skill, "failed", "blocked")
            return
        if skill.search_started is None:
            skill.search_started = now
            return
        if now - skill.search_started <= SEARCH_WINDOW:
            return
        if not fresh:
            self._finalize(skill, "failed", "stale_state")
        elif skill.mined >= skill.requested:
            self._finalize(skill, "failed", "drop_unavailable")
        elif skill.saw_path_failure:
            self._finalize(skill, "failed", "path_not_found")
        else:
            self._finalize(skill, "failed", skill.not_found_reason)

    # ---- receipts --------------------------------------------------------
    def _validate_receipt(self, descriptor, parsed):
        """Descriptor-based validation common to current, settled and terminal receipts.

        actionId is resolved to its immutable descriptor first, so a wrong sequence, payload kind,
        target canonical id, count or status/reason pair is `conflicting_result` even for duplicates.
        """
        payload = parsed["payload"]
        if parsed["actionSequence"] != descriptor["sequence"]:
            raise SkillSyncError("conflicting_result")
        if parsed["payload_kind"] != descriptor["payloadField"]:
            raise SkillSyncError("conflicting_result")
        if payload["id"] != descriptor["name"]:
            raise SkillSyncError("conflicting_result")
        status, reason, count = parsed["status"], parsed["reason"], payload["count"]
        if status == "running":
            if reason != "accepted" or count != 0:
                raise SkillSyncError("conflicting_result")
        elif status == "succeeded":
            if reason != "completed" or not 1 <= count <= descriptor["maxCount"]:
                raise SkillSyncError("conflicting_result")
        elif count != 0:   # failed / cancelled
            raise SkillSyncError("conflicting_result")

    @staticmethod
    def _settled_matches(recorded, parsed):
        return recorded["status"] == parsed["status"] and recorded["reason"] == parsed["reason"] \
            and recorded["count"] == parsed["payload"]["count"]

    def _apply_result(self, skill, parsed):
        descriptor = skill.descriptors.get(parsed["actionId"])
        if descriptor is None:
            raise SkillSyncError("unknown_action")
        self._validate_receipt(descriptor, parsed)
        recorded = skill.settled.get(parsed["actionId"])
        if recorded is not None:
            # A duplicate terminal is ACKed; a conflicting terminal is rejected; a late running never
            # resurrects state but is still a valid (if pointless) observation of the same action.
            if parsed["status"] == "running" or self._settled_matches(recorded, parsed):
                return
            raise SkillSyncError("conflicting_result")
        if skill.phase == "terminal":
            # The terminal result is immutable: record a late known receipt but change no progress.
            if parsed["status"] != "running":
                skill.settled[parsed["actionId"]] = {"status": parsed["status"], "reason": parsed["reason"],
                                                     "count": parsed["payload"]["count"]}
            return
        current = skill.current
        if parsed["status"] == "running":
            if current is not None and current["actionId"] == parsed["actionId"]:
                current["running"] = True
            return
        if current is None or current["actionId"] != parsed["actionId"]:
            raise SkillSyncError("unknown_action")
        count = parsed["payload"]["count"]
        now = self.clock()
        field = current["payloadField"]
        cache_key = current["candidateKey"]
        issued_sequence = current["observationSequence"]
        skill.settled[parsed["actionId"]] = {"status": parsed["status"], "reason": parsed["reason"], "count": count}
        skill.unresolved = False
        skill.current = None
        if parsed["status"] == "succeeded":
            if field == "item":
                skill.acquired += count
            else:
                skill.mined += count
            skill.failures = 0
            skill.fence_sequence = issued_sequence
            skill.fence_until = now + SEARCH_WINDOW
            # The count is now settled exactly once, so a cancel/replace that already won the race
            # must close here and never issue a replacement action or reach completed.
            if self._close_if_settled(skill):
                return
            skill.phase = "selecting"
            skill.on_action_succeeded(current, count, now)
            self._advance_selection(skill)
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
            skill.exclude(cache_key, field)
            self._finalize(skill, "cancelled", skill.cancel_reason or "replaced")
            return
        if parsed["reason"] in TERMINAL_FAILURE_REASONS:
            skill.exclude(cache_key, field)
            self._finalize(skill, "failed", parsed["reason"])
            return
        if parsed["reason"] == "blocked":
            skill.exclude(cache_key, field)
            skill.saw_blocked = True
            skill.phase = "selecting"
            skill.on_action_failed(current, parsed["reason"], now)
            self._advance_selection(skill)
            return
        skill.failures += 1
        if parsed["reason"] in PATH_FAILURES:
            skill.saw_path_failure = True
        if parsed["reason"] in FAILURE_REASONS:
            skill.exclude(cache_key, field)
        skill.on_action_failed(current, parsed["reason"], now)
        if skill.failures >= MAX_FAILURES:
            self._finalize(skill, "failed", "retry_exhausted")
        else:
            skill.phase = "selecting"
            self._advance_selection(skill)

    def _advance_selection(self, skill):
        self._step(skill)
