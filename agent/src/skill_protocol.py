"""Strict v2 Skill protocol validation. No Minecraft, HTTP, or provider dependencies."""
import re

from state_cache import identity

SUPPORTED_ITEMS = ("minecraft:log", "minecraft:cobblestone", "minecraft:iron_ingot",
                   "minecraft:planks", "minecraft:stick")
LEGACY_GOALS = ("follow_owner", "stop", "look_at_owner", "pickup_item", "deposit_items")
CAPABILITIES = ("collect_drop_v1",)

TOP_STATUS = ("idle", "thinking", "running", "completed", "failed", "cancelled")
PHASES = ("selecting", "waiting_action", "cancelling", "terminal")
SKILL_TERMINAL = ("completed", "failed", "cancelled")
ACTION_STATUS = ("running", "succeeded", "failed", "cancelled")

# Reasons a Skill action receipt may carry. `target_lost` / `target_not_ready` are Skill-only.
ACTION_REASONS = ("accepted", "completed", "target_lost", "target_not_ready", "path_not_found",
                  "inventory_full", "inventory_empty", "owner_unavailable", "companion_unavailable",
                  "unsafe_state", "expired", "disconnected", "stopped", "replaced", "action_failed")
FAILURE_REASONS = ("target_lost", "target_not_ready", "path_not_found", "inventory_full",
                   "unsafe_state", "owner_unavailable", "companion_unavailable", "expired",
                   "disconnected", "action_failed")
PATH_FAILURES = ("path_not_found",)

SKILL_REASONS = ("completed", "no_item_in_range", "path_not_found", "retry_exhausted",
                 "inventory_full", "unsafe_state", "owner_unavailable", "companion_unavailable",
                 "stale_state", "expired", "disconnected", "stopped", "replaced", "action_failed")

ITEM_NAME = re.compile(r"[A-Za-z0-9_.:-]{1,128}")
MAX_REVISION = 2147483647


class SkillError(Exception):
    pass


class SkillRequestError(SkillError):
    """Malformed or unsupported request. Maps to HTTP 400."""

    def __init__(self, code):
        super().__init__(code)
        self.code = code


class SkillSyncError(SkillError):
    """Conflict with current authority/epoch/session. Maps to HTTP 409."""

    def __init__(self, code):
        super().__init__(code)
        self.code = code


class SkillBusyError(SkillError):
    """Capacity exhausted. Maps to HTTP 503."""

    def __init__(self, code="busy"):
        super().__init__(code)
        self.code = code


def _keys(data, names):
    if not isinstance(data, dict) or set(data) != set(names):
        raise SkillRequestError("invalid_request")


def _version(data, expected):
    if type(data.get("version")) is not int or data["version"] != expected:
        raise SkillRequestError("invalid_request")


def revision(value):
    if type(value) is not int or type(value) is bool or not 0 <= value <= MAX_REVISION:
        raise SkillRequestError("invalid_request")
    return value


def validate_open(data):
    _keys(data, {"version", "session"})
    _version(data, 2)
    return {"version": 2, "session": identity(data["session"])}


def parse_collect_drop(goal):
    if not isinstance(goal, dict) or set(goal) - {"type", "target", "count", "constraints"} \
            or not {"type", "target", "count"} <= set(goal):
        raise SkillRequestError("invalid_request")
    if goal["type"] != "collect_drop":
        raise SkillRequestError("invalid_request")
    target = goal["target"]
    if not isinstance(target, dict) or set(target) != {"item"} or not isinstance(target["item"], str):
        raise SkillRequestError("invalid_request")
    item = target["item"]
    if not ITEM_NAME.fullmatch(item):
        raise SkillRequestError("invalid_request")
    if item not in SUPPORTED_ITEMS:
        raise SkillRequestError("unsupported_item")
    count = goal["count"]
    if type(count) is not int or type(count) is bool or not 1 <= count <= 64:
        raise SkillRequestError("invalid_request")
    if "constraints" in goal:
        constraints = goal["constraints"]
        if not isinstance(constraints, list) or constraints:
            raise SkillRequestError("unsupported_constraint")
    return {"type": "collect_drop", "target": {"item": item}, "count": count, "constraints": []}


def validate_goal(data):
    _keys(data, {"version", "session", "daemonEpoch", "goalRevision", "goal"})
    _version(data, 2)
    session = identity(data["session"])
    epoch = identity(data["daemonEpoch"])
    rev = revision(data["goalRevision"])
    goal = data["goal"]
    if goal is None:
        parsed = None
    elif isinstance(goal, str):
        if goal not in LEGACY_GOALS:
            raise SkillRequestError("unsupported_goal")
        parsed = goal
    elif isinstance(goal, dict):
        parsed = parse_collect_drop(goal)
    else:
        raise SkillRequestError("invalid_request")
    return {"version": 2, "session": session, "daemonEpoch": epoch, "goalRevision": rev, "goal": parsed}


def validate_result(data):
    if not isinstance(data, dict):
        raise SkillRequestError("invalid_request")
    if "skillInstanceId" in data:
        _keys(data, {"version", "session", "daemonEpoch", "goalRevision", "skillInstanceId",
                     "actionId", "actionSequence", "status", "reason", "acquired"})
        _version(data, 2)
        status = data["status"]
        if type(status) is not str or status not in ACTION_STATUS:
            raise SkillRequestError("invalid_request")
        reason = data["reason"]
        if type(reason) is not str or reason not in ACTION_REASONS:
            raise SkillRequestError("invalid_request")
        seq = data["actionSequence"]
        if type(seq) is not int or type(seq) is bool or not 1 <= seq <= 100000:
            raise SkillRequestError("invalid_request")
        acquired = data["acquired"]
        if not isinstance(acquired, dict) or set(acquired) != {"item", "count"}:
            raise SkillRequestError("invalid_request")
        if not isinstance(acquired["item"], str) or not ITEM_NAME.fullmatch(acquired["item"]):
            raise SkillRequestError("invalid_request")
        count = acquired["count"]
        if type(count) is not int or type(count) is bool or not 0 <= count <= 64:
            raise SkillRequestError("invalid_request")
        if status == "running" and (reason != "accepted" or count != 0):
            raise SkillRequestError("invalid_request")
        if status != "succeeded" and count != 0:
            raise SkillRequestError("invalid_request")
        return {"kind": "skill", "version": 2, "session": identity(data["session"]),
                "daemonEpoch": identity(data["daemonEpoch"]), "goalRevision": revision(data["goalRevision"]),
                "skillInstanceId": identity(data["skillInstanceId"]), "actionId": identity(data["actionId"]),
                "actionSequence": seq, "status": status, "reason": reason,
                "acquired": {"item": acquired["item"], "count": count}}
    _keys(data, {"version", "session", "daemonEpoch", "goalRevision", "actionId", "status", "reason"})
    _version(data, 2)
    status = data["status"]
    if type(status) is not str or status not in ACTION_STATUS:
        raise SkillRequestError("invalid_request")
    reason = data["reason"]
    if type(reason) is not str or reason not in ACTION_REASONS:
        raise SkillRequestError("invalid_request")
    return {"kind": "legacy", "version": 2, "session": identity(data["session"]),
            "daemonEpoch": identity(data["daemonEpoch"]), "goalRevision": revision(data["goalRevision"]),
            "actionId": identity(data["actionId"]), "status": status, "reason": reason}


def validate_status(data):
    _keys(data, {"version", "session", "daemonEpoch", "skillInstanceId"})
    _version(data, 2)
    return {"version": 2, "session": identity(data["session"]),
            "daemonEpoch": identity(data["daemonEpoch"]), "skillInstanceId": identity(data["skillInstanceId"])}
