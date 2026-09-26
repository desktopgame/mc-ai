"""Shared v2 execution authority: boot id, per-companion ownership and the action ledger.

The registry never calls into the goal or skill managers while holding its lock. It only
records authority so both sides agree on who may issue work for a companion and which
action receipts have already been settled.
"""
import threading
import time
import uuid
from collections import OrderedDict

MAX_LEDGER = 2048
MAX_OWNERS = 64


class ExecutionRegistry:
    def __init__(self, clock=time.monotonic):
        self.clock = clock
        self.epoch = str(uuid.uuid4())
        self.lock = threading.Lock()
        self.opened = OrderedDict()          # session -> {"companion", "dimension", "at"}
        self.owner_by_companion = OrderedDict()  # companionId -> session
        self.ledger = OrderedDict()          # (session, actionId) -> {"status", "reason", "count", "item"}

    def open(self, session, companion, dimension):
        """Register a v2 session. Returns sessions invalidated on the same companion."""
        invalidated = []
        with self.lock:
            previous = self.opened.get(session)
            if previous is not None and previous["companion"] == companion:
                previous["dimension"] = dimension
                previous["at"] = self.clock()
                return []
            owner = self.owner_by_companion.get(companion)
            if owner is not None and owner != session:
                invalidated.append(owner)
                self.opened.pop(owner, None)
            self.opened[session] = {"companion": companion, "dimension": dimension, "at": self.clock()}
            self.opened.move_to_end(session)
            self.owner_by_companion[companion] = session
            self.owner_by_companion.move_to_end(companion)
            while len(self.opened) > MAX_OWNERS:
                old, _ = self.opened.popitem(last=False)
                for key, value in list(self.owner_by_companion.items()):
                    if value == old:
                        del self.owner_by_companion[key]
        return invalidated

    def is_open(self, session):
        with self.lock:
            return session in self.opened

    def owner_of(self, companion):
        with self.lock:
            return self.owner_by_companion.get(companion)

    def close(self, session):
        with self.lock:
            entry = self.opened.pop(session, None)
            if entry is not None:
                owner = self.owner_by_companion.get(entry["companion"])
                if owner == session:
                    del self.owner_by_companion[entry["companion"]]

    def record(self, session, action_id, status, reason, count=0, item=None):
        with self.lock:
            key = (session, action_id)
            seen = self.ledger.get(key)
            if seen is not None:
                return seen
            value = {"status": status, "reason": reason, "count": count, "item": item}
            self.ledger[key] = value
            self.ledger.move_to_end(key)
            while len(self.ledger) > MAX_LEDGER:
                self.ledger.popitem(last=False)
            return value

    def known(self, session, action_id):
        with self.lock:
            return self.ledger.get((session, action_id))
