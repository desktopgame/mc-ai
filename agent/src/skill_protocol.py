"""Strict v2 Skill protocol validation. No Minecraft, HTTP, or provider dependencies."""
import re

from state_cache import identity

SUPPORTED_ITEMS = ("minecraft:log", "minecraft:cobblestone", "minecraft:iron_ingot",
                   "minecraft:planks", "minecraft:stick")
SUPPORTED_BLOCKS = ("minecraft:log", "minecraft:log2", "minecraft:cobblestone", "minecraft:stone",
                    "minecraft:coal_ore", "minecraft:iron_ore", "minecraft:gold_ore",
                    "minecraft:diamond_ore", "minecraft:dirt", "minecraft:sand", "minecraft:gravel")
LEGACY_GOALS = ("follow_owner", "stop", "look_at_owner", "pickup_item", "deposit_items")  # v1 /goal only
CAPABILITIES = ("collect_drop_v1", "mine_v1", "collect_block_v1")

# collect_block MVP: the block -> collected item mapping is an explicit table, not a name convention.
COLLECT_BLOCK_TARGETS = {"minecraft:log": {"block": "minecraft:log", "item": "minecraft:log"}}

TOP_STATUS = ("idle", "thinking", "running", "completed", "failed", "cancelled")
PHASES = ("selecting", "waiting_action", "cancelling", "terminal")
SKILL_TERMINAL = ("completed", "failed", "cancelled")
ACTION_STATUS = ("running", "succeeded", "failed", "cancelled")

# Reasons a Skill action receipt may carry. `target_lost` / `target_not_ready` / `tool_unavailable`
# are Skill-only.
ACTION_REASONS = ("accepted", "completed", "target_lost", "target_not_ready", "tool_unavailable",
                  "blocked", "path_not_found", "inventory_full", "inventory_empty", "owner_unavailable",
                  "companion_unavailable", "unsafe_state", "expired", "disconnected", "stopped",
                  "replaced", "action_failed")
FAILURE_REASONS = ("target_lost", "target_not_ready", "tool_unavailable", "blocked", "path_not_found",
                   "inventory_full", "unsafe_state", "owner_unavailable", "companion_unavailable",
                   "expired", "disconnected", "action_failed")
# A missing required tool cannot be fixed by trying another target of the same kind.
TERMINAL_FAILURE_REASONS = ("tool_unavailable",)
PATH_FAILURES = ("path_not_found",)

SKILL_REASONS = ("completed", "no_item_in_range", "no_block_in_range", "drop_unavailable", "path_not_found",
                 "retry_exhausted", "inventory_full", "tool_unavailable", "blocked", "unsafe_state",
                 "owner_unavailable", "companion_unavailable", "stale_state", "expired",
                 "disconnected", "stopped", "replaced", "action_failed")

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


def _parse_target(goal, target_key, allowlist, code):
    if not isinstance(goal, dict) or set(goal) - {"type", "target", "count", "constraints"} \
            or not {"type", "target", "count"} <= set(goal):
        raise SkillRequestError("invalid_request")
    target = goal["target"]
    if not isinstance(target, dict) or set(target) != {target_key} or not isinstance(target[target_key], str):
        raise SkillRequestError("invalid_request")
    name = target[target_key]
    if not ITEM_NAME.fullmatch(name):
        raise SkillRequestError("invalid_request")
    if name not in allowlist:
        raise SkillRequestError(code)
    count = goal["count"]
    if type(count) is not int or type(count) is bool or not 1 <= count <= 64:
        raise SkillRequestError("invalid_request")
    if "constraints" in goal and (not isinstance(goal["constraints"], list) or goal["constraints"]):
        raise SkillRequestError("unsupported_constraint")
    return {target_key: name, "count": count}


def parse_collect_drop(goal):
    if goal.get("type") != "collect_drop":
        raise SkillRequestError("invalid_request")
    parsed = _parse_target(goal, "item", SUPPORTED_ITEMS, "unsupported_item")
    return {"type": "collect_drop", "target": {"item": parsed["item"]}, "count": parsed["count"], "constraints": []}


def parse_mine(goal):
    if goal.get("type") != "mine":
        raise SkillRequestError("invalid_request")
    parsed = _parse_target(goal, "block", SUPPORTED_BLOCKS, "unsupported_block")
    # mine is a primitive check: exactly one block. Repetition belongs to a future collect_block Skill.
    if parsed["count"] != 1:
        raise SkillRequestError("unsupported_count")
    return {"type": "mine", "target": {"block": parsed["block"]}, "count": 1, "constraints": []}


def parse_collect_block(goal):
    if goal.get("type") != "collect_block":
        raise SkillRequestError("invalid_request")
    parsed = _parse_target(goal, "block", tuple(COLLECT_BLOCK_TARGETS), "unsupported_block")
    return {"type": "collect_block", "target": {"block": parsed["block"]},
            "count": parsed["count"], "constraints": []}


def parse_goal_object(goal):
    if not isinstance(goal, dict):
        raise SkillRequestError("invalid_request")
    kind = goal.get("type")
    if kind == "collect_drop":
        return parse_collect_drop(goal)
    if kind == "mine":
        return parse_mine(goal)
    if kind == "collect_block":
        return parse_collect_block(goal)
    raise SkillRequestError("unsupported_goal")


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
        # v2 no longer accepts legacy string goals: the shared revision/ownership contract between
        # the legacy GoalManager and the Skill layer does not exist, so the union is closed here.
        # Legacy operations continue to use the v1 /goal path.
        raise SkillRequestError("legacy_goal_unsupported")
    else:
        parsed = parse_goal_object(goal)
    return {"version": 2, "session": session, "daemonEpoch": epoch, "goalRevision": rev, "goal": parsed}


def _parse_payload(data, key, id_key, count):
    payload = data[key]
    if not isinstance(payload, dict) or set(payload) != {id_key, "count"}:
        raise SkillRequestError("invalid_request")
    if not isinstance(payload[id_key], str) or not ITEM_NAME.fullmatch(payload[id_key]):
        raise SkillRequestError("invalid_request")
    count = payload["count"]
    if type(count) is not int or type(count) is bool or not 0 <= count <= 64:
        raise SkillRequestError("invalid_request")
    return {"field": id_key, "id": payload[id_key], "count": count}


def validate_result(data):
    if not isinstance(data, dict):
        raise SkillRequestError("invalid_request")
    if "skillInstanceId" in data:
        base = {"version", "session", "daemonEpoch", "goalRevision", "skillInstanceId",
                "actionId", "actionSequence", "status", "reason"}
        if "acquired" in data and "destroyed" in data:
            raise SkillRequestError("invalid_request")
        _keys(data, base | ({"acquired"} if "acquired" in data else {"destroyed"}))
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
        if "acquired" in data:
            payload = _parse_payload(data, "acquired", "item", 0)
            kind = "item"
        else:
            payload = _parse_payload(data, "destroyed", "block", 0)
            kind = "block"
        if status == "running" and (reason != "accepted" or payload["count"] != 0):
            raise SkillRequestError("invalid_request")
        if status != "succeeded" and payload["count"] != 0:
            raise SkillRequestError("invalid_request")
        return {"kind": "skill", "version": 2, "session": identity(data["session"]),
                "daemonEpoch": identity(data["daemonEpoch"]), "goalRevision": revision(data["goalRevision"]),
                "skillInstanceId": identity(data["skillInstanceId"]), "actionId": identity(data["actionId"]),
                "actionSequence": seq, "status": status, "reason": reason,
                "payload_kind": kind, "payload": payload}
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
