"""Bounded terminal-event outbox and presentation ledger, independent of Skill active state.

The Skill layer registers an immutable snapshot exactly once when a terminal result is finalized.
This store keeps those snapshots for later read-only retrieval and tracks the per-identity
presentation/ACK state used by the Social delivery endpoints. No HTTP, LLM or Skill re-execution.
"""
import copy
import threading
import time
from collections import OrderedDict

MAX_EVENTS = 100
EVENT_TTL = 600.0
MAX_PAGE = 8
PRESENTATION_STATES = ("not_started", "generating", "ready", "delivered", "suppressed")


class TerminalError(Exception):
    """Terminal identity / binding conflict or unknown event. `.code` maps to an HTTP status."""

    def __init__(self, code):
        super().__init__(code)
        self.code = code


def identity_of(event):
    return (event["daemonEpoch"], event["session"], event["skillInstanceId"], event["terminalId"])


class TerminalEventStore:
    def __init__(self, clock=time.monotonic):
        self.clock = clock
        self.lock = threading.Lock()
        self.sequences = {}                 # (epoch, session) -> last assigned sequence
        self.by_session = {}                # (epoch, session) -> OrderedDict(identity -> event)
        self.presentations = OrderedDict()  # identity -> {binding, state, say, variantId, mode, ack}
        self.tombstones = OrderedDict()     # identity -> "delivered" | "suppressed" (bounded)
        self.overflow = set()               # (epoch, session) sticky overflow
        self.recorded_at = {}               # identity -> clock

    # ---- outbox ----------------------------------------------------------
    def record(self, snapshot):
        """Register a finalized terminal snapshot exactly once. Assigns the monotonic eventSequence.

        Order matters: an identity is checked before any sequence is assigned. A duplicate with the
        same immutable payload returns the existing event without consuming a sequence, and a
        duplicate with different content is a conflict. A terminal that was already closed by a
        displayed/suppressed ACK is never regenerated.
        """
        epoch, session = snapshot["daemonEpoch"], snapshot["session"]
        identity = (epoch, session, snapshot["skillInstanceId"], snapshot["terminalId"])
        payload = {key: value for key, value in snapshot.items() if key != "eventSequence"}
        with self.lock:
            bucket = self.by_session.setdefault((epoch, session), OrderedDict())
            existing = bucket.get(identity)
            if existing is not None:
                if {k: v for k, v in existing.items() if k != "eventSequence"} == payload:
                    return copy.deepcopy(existing)
                raise TerminalError("terminal_identity_conflict")
            if identity in self.tombstones:
                # Already delivered/suppressed/expired: never resurrect or re-sequence it.
                return None
            sequence = self.sequences.get((epoch, session), 0) + 1
            self.sequences[(epoch, session)] = sequence
            event = dict(payload)
            event["eventSequence"] = sequence
            bucket[identity] = event
            self.recorded_at[identity] = self.clock()
            self._evict()
            return copy.deepcopy(event)

    def snapshot(self, epoch, session, after_sequence=None, limit=MAX_PAGE):
        with self.lock:
            self._expire()
            bucket = self.by_session.get((epoch, session), OrderedDict())
            events = [copy.deepcopy(e) for e in bucket.values()
                      if after_sequence is None or e["eventSequence"] > after_sequence]
            page = events[:limit]
            return {"events": page, "hasMore": len(events) > len(page),
                    "overflow": (epoch, session) in self.overflow}

    def _evict(self):
        while len(self.recorded_at) > MAX_EVENTS:
            identity = next(iter(self.recorded_at))
            self._drop(identity)

    def _expire(self):
        now = self.clock()
        for identity in list(self.recorded_at):
            if now - self.recorded_at[identity] > EVENT_TTL:
                self._drop(identity)

    def _drop(self, identity):
        epoch, session = identity[0], identity[1]
        bucket = self.by_session.get((epoch, session))
        if bucket is not None:
            bucket.pop(identity, None)
        self.recorded_at.pop(identity, None)
        self.tombstones[identity] = "expired"
        self.overflow.add((epoch, session))
        while len(self.tombstones) > MAX_EVENTS:
            self.tombstones.popitem(last=False)

    # ---- presentation ledger --------------------------------------------
    def present(self, epoch, session, skillInstanceId, terminalId, conversationSession, player, deliveryId):
        identity = (epoch, session, skillInstanceId, terminalId)
        binding = (conversationSession, player, deliveryId)
        with self.lock:
            entry = self.presentations.get(identity)
            if entry is not None:
                if entry["binding"] != binding:
                    raise TerminalError("binding_conflict")
                return self._presentation_result(entry)
            self._reject_same_skill(identity)
            event = self.by_session.get((epoch, session), {}).get(identity)
            if event is None:
                if identity in self.tombstones:
                    raise TerminalError("terminal_gone")
                raise TerminalError("unknown_terminal")
            from terminal_presentation import render_fallback
            say = render_fallback(event)
            entry = {"binding": binding, "state": "ready", "say": say,
                     "variantId": "fallback", "mode": "fallback", "ack": None}
            self.presentations[identity] = entry
            self.presentations.move_to_end(identity)
            return self._presentation_result(entry)

    def deliver(self, epoch, session, skillInstanceId, terminalId, conversationSession, player,
                deliveryId, outcome, variant_id):
        identity = (epoch, session, skillInstanceId, terminalId)
        binding = (conversationSession, player, deliveryId)
        with self.lock:
            entry = self.presentations.get(identity)
            if entry is None:
                if identity in self.tombstones:
                    return   # duplicate ACK for an already-closed terminal
                raise TerminalError("unknown_terminal")
            if entry["binding"] != binding:
                raise TerminalError("binding_conflict")
            if entry["state"] in ("delivered", "suppressed"):
                if entry["ack"] == (outcome, variant_id):
                    return
                raise TerminalError("ack_conflict")
            entry["state"] = "delivered" if outcome == "displayed" else "suppressed"
            entry["ack"] = (outcome, variant_id)
            self.tombstones[identity] = entry["state"]
            # Close the outbox entry: an ACKed terminal is never delivered again by a later poll,
            # even from afterSequence=0 or after the Forge loses its cursor.
            self._close(identity)
            while len(self.tombstones) > MAX_EVENTS:
                self.tombstones.popitem(last=False)

    def _close(self, identity):
        bucket = self.by_session.get((identity[0], identity[1]))
        if bucket is not None:
            bucket.pop(identity, None)
        self.recorded_at.pop(identity, None)

    def _reject_same_skill(self, identity):
        skill_prefix = identity[:3]
        for other in list(self.presentations) + list(self.tombstones):
            if other[:3] == skill_prefix and other != identity:
                raise TerminalError("terminal_conflict")

    @staticmethod
    def _presentation_result(entry):
        return {"say": entry["say"], "variantId": entry["variantId"], "mode": entry["mode"]}
