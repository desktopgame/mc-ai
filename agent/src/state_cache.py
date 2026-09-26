"""Bounded, transactional observation cache. No conversation data is accepted."""
import copy
import re
import threading
import time
from collections import OrderedDict, deque
from decision import number, position


class SyncError(Exception):
    pass


def identity(value):
    if not isinstance(value, str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,80}", value):
        raise ValueError("invalid_identity")
    return value


def health(value): return number(value, 0, 2048)


def inventory(value):
    if not isinstance(value, dict) or len(value) > 128:
        raise ValueError("invalid_inventory")
    result = {}
    for key, count in value.items():
        if not re.fullmatch(r"[A-Za-z0-9_.:-]{1,128}", key) or type(count) is not int or not 1 <= count <= 100000:
            raise ValueError("invalid_inventory_item")
        result[key] = count
    return result


def hostile(value):
    if not isinstance(value, dict) or set(value) != {"type", "distance"}:
        raise ValueError("invalid_hostile")
    kind = value["type"]
    if not isinstance(kind, str) or not re.fullmatch(r"[A-Za-z0-9_.:-]{1,128}", kind):
        raise ValueError("invalid_hostile_type")
    return {"type": kind, "distance": number(value["distance"], 0, 32)}


TASKS = ("idle", "follow", "look")
RESULTS = ("none", "stopped", "following", "looking", "near_owner", "owner_unavailable",
           "look_completed", "companion_died", "owner_out_of_range", "path_not_found", "path_retrying")


def task(value):
    if not isinstance(value, dict) or set(value) != {"task", "result"} or value["task"] not in TASKS or value["result"] not in RESULTS:
        raise ValueError("invalid_task")
    return dict(value)


def validate_state(value):
    if not isinstance(value, dict) or set(value) != {"dimension", "owner", "companion", "hostiles"}:
        raise ValueError("invalid_state")
    dimension = value["dimension"]
    if type(dimension) is not int or not -2147483648 <= dimension <= 2147483647:
        raise ValueError("invalid_dimension")
    owner = value["owner"]
    if not isinstance(owner, dict) or set(owner) != {"position", "health", "inventory"}:
        raise ValueError("invalid_owner")
    companion = value["companion"]
    if companion is not None:
        if not isinstance(companion, dict) or set(companion) != {"id", "position", "health", "task", "result"}:
            raise ValueError("invalid_companion")
        companion = {"id": identity(companion["id"]), "position": position(companion["position"]),
                     "health": health(companion["health"]), **task({k: companion[k] for k in ("task", "result")})}
    hostiles = value["hostiles"]
    if not isinstance(hostiles, dict) or len(hostiles) > 16:
        raise ValueError("invalid_hostiles")
    return {"dimension": dimension,
            "owner": {"position": position(owner["position"]), "health": health(owner["health"]), "inventory": inventory(owner["inventory"])},
            "companion": companion, "hostiles": {identity(k): hostile(v) for k, v in hostiles.items()}}


def apply_event(state, event):
    if not isinstance(event, dict): raise ValueError("invalid_event")
    kind = event.get("type")
    if kind in ("position_changed_significantly", "health_changed"):
        field = "position" if kind == "position_changed_significantly" else "health"
        if set(event) != {"type", "entity", field} or event["entity"] not in ("owner", "companion"):
            raise ValueError("invalid_entity_event")
        entity = state[event["entity"]]
        if entity is None: raise ValueError("missing_companion")
        entity[field] = position(event[field]) if field == "position" else health(event[field])
    elif kind == "inventory_changed":
        if set(event) != {"type", "added", "removed"}: raise ValueError("invalid_inventory_event")
        added, removed = inventory(event["added"]), inventory(event["removed"])
        if set(added) & set(removed): raise ValueError("overlapping_inventory_delta")
        items = state["owner"]["inventory"]
        for key, count in removed.items():
            if items.get(key, 0) < count: raise SyncError("inventory_mismatch")
            items[key] -= count
            if not items[key]: del items[key]
        for key, count in added.items(): items[key] = items.get(key, 0) + count
    elif kind in ("task_changed", "task_completed", "task_failed"):
        if set(event) != {"type", "task", "result"} or state["companion"] is None:
            raise ValueError("invalid_task_event")
        state["companion"].update(task({k: event[k] for k in ("task", "result")}))
    elif kind in ("hostile_entered_range", "hostile_updated"):
        if set(event) != {"type", "id", "observation"}: raise ValueError("invalid_hostile_event")
        key = identity(event["id"])
        if (key in state["hostiles"]) != (kind == "hostile_updated"):
            raise SyncError("hostile_mismatch")
        state["hostiles"][key] = hostile(event["observation"])
    elif kind == "hostile_left_range":
        if set(event) != {"type", "id"}: raise ValueError("invalid_hostile_event")
        key = identity(event["id"])
        if key not in state["hostiles"]: raise SyncError("hostile_mismatch")
        del state["hostiles"][key]
    else:
        raise ValueError("unknown_event")


class StateCache:
    def __init__(self, clock=time.monotonic):
        self.clock = clock
        self.entries = OrderedDict()
        self.lock = threading.Lock()

    def update(self, payload, snapshot=False):
        if not isinstance(payload, dict) or type(payload.get("version")) is not int or payload["version"] != 1:
            raise ValueError("invalid_version")
        if set(payload) != {"version", "session", "sequence", "state" if snapshot else "events"}:
            raise ValueError("invalid_envelope")
        session = identity(payload["session"])
        seq = payload["sequence"]
        if type(seq) is not int or not 0 <= seq <= 2147483647: raise ValueError("invalid_sequence")
        with self.lock:
            previous = self.entries.get(session)
            if snapshot:
                if previous and seq <= previous["sequence"]: raise SyncError("out_of_order_snapshot")
                state = validate_state(payload["state"])
                history = deque(maxlen=100)
            else:
                if previous is None: raise SyncError("snapshot_required")
                if seq != previous["sequence"] + 1: raise SyncError("sequence_mismatch")
                events = payload["events"]
                if not isinstance(events, list) or len(events) > 64: raise ValueError("invalid_events")
                state = copy.deepcopy(previous["state"])
                history = copy.deepcopy(previous["history"])
                for event in events:
                    apply_event(state, event)
                    history.append(copy.deepcopy(event))
                state = validate_state(state)
            self.entries[session] = {"sequence": seq, "state": state, "history": history, "updated": self.clock()}
            self.entries.move_to_end(session)
            while len(self.entries) > 32: self.entries.popitem(last=False)
            return {"version": 1, "sequence": seq, "synced": True}

    def view(self, payload):
        if not isinstance(payload, dict) or type(payload.get("version")) is not int or payload["version"] != 1 or set(payload) - {"version", "session"}:
            raise ValueError("invalid_state_query")
        with self.lock:
            if not self.entries: raise SyncError("snapshot_required")
            session = identity(payload["session"]) if "session" in payload else next(reversed(self.entries))
            entry = self.entries.get(session)
            if entry is None: raise SyncError("snapshot_required")
            age = max(0, self.clock() - entry["updated"])
            return {"version": 1, "session": session, "sequence": entry["sequence"],
                    "age_seconds": round(age, 2), "stale": age > 15,
                    "state": copy.deepcopy(entry["state"]), "events": list(copy.deepcopy(entry["history"]))}
