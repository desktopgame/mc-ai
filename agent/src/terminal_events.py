"""Bounded terminal-event outbox and presentation ledger, independent of Skill active state.

The Skill layer registers an immutable snapshot exactly once when a terminal result is finalized.
This store keeps those snapshots for later read-only retrieval and tracks the per-identity
presentation/ACK state used by the Social delivery endpoints. No HTTP, LLM or Skill re-execution.

Bounded: outbox, presentation ledger and the closed-identity ledger all have hard limits. A closed
identity keeps only the minimal fields needed for idempotent ACK and duplicate/conflict detection
(state, binding, ack, immutable payload fingerprint); the heavy presentation text is released.
"""
import copy
import hashlib
import json
import threading
import time
from collections import OrderedDict

MAX_EVENTS = 100
MAX_CLOSED = 100
MAX_CLOSED_DIGESTS = 1024
MAX_PRESENTATIONS = 100
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


def _fingerprint(payload):
    """Deterministic, immutable representation of a terminal snapshot (excluding eventSequence)."""
    return json.dumps({k: v for k, v in payload.items() if k != "eventSequence"},
                      sort_keys=True, ensure_ascii=False, separators=(",", ":"))


def _identity_digest(identity):
    """Compact, stable key for the closed-identity ledger, so exact records can be evicted early."""
    return hashlib.blake2b(repr(identity).encode("utf-8"), digest_size=8).hexdigest()


class TerminalEventStore:
    def __init__(self, clock=time.monotonic):
        self.clock = clock
        self.lock = threading.Lock()
        self.sequences = {}                 # (epoch, session) -> last assigned sequence
        self.by_session = {}                # (epoch, session) -> OrderedDict(identity -> event)
        self.presentations = OrderedDict()  # identity -> {binding, state, say, variantId, mode, ack, fingerprint}
        self.closed = OrderedDict()         # identity -> {state, binding, ack, fingerprint} (bounded)
        self.closed_digest = OrderedDict()  # identity digest -> fingerprint (bounded, outlives exact records)
        self.overflow = set()               # (epoch, session) sticky overflow
        self.recorded_at = {}               # identity -> clock

    # ---- outbox ----------------------------------------------------------
    def record(self, snapshot):
        """Register a finalized terminal snapshot exactly once. Assigns the monotonic eventSequence.

        Identity is resolved before any sequence is assigned. A duplicate with the same immutable
        payload returns the existing event without consuming a sequence; different content is a
        conflict. A terminal already closed by an ACK is compared by fingerprint too, so a closed
        identity can never be regenerated, and a conflicting payload after the ACK is still rejected.
        """
        epoch, session = snapshot["daemonEpoch"], snapshot["session"]
        identity = (epoch, session, snapshot["skillInstanceId"], snapshot["terminalId"])
        payload = {key: value for key, value in snapshot.items() if key != "eventSequence"}
        fingerprint = _fingerprint(payload)
        with self.lock:
            bucket = self.by_session.setdefault((epoch, session), OrderedDict())
            existing = bucket.get(identity)
            if existing is not None:
                if _fingerprint(existing) == fingerprint:
                    return copy.deepcopy(existing)
                raise TerminalError("terminal_identity_conflict")
            closed = self.closed.get(identity)
            if closed is not None:
                if closed["fingerprint"] == fingerprint:
                    return None   # already delivered/suppressed/expired: never resurrect
                raise TerminalError("terminal_identity_conflict")
            known = self.closed_digest.get(_identity_digest(identity))
            if known is not None:
                # The exact closed record may have been evicted, but the compact digest ledger still
                # remembers the identity: never regenerate it, and reject a changed payload.
                if known == fingerprint:
                    return None
                raise TerminalError("terminal_identity_conflict")
            sequence = self.sequences.get((epoch, session), 0) + 1
            self.sequences[(epoch, session)] = sequence
            event = copy.deepcopy(payload)   # the store owns its snapshot, including nested objects
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
            self._drop(next(iter(self.recorded_at)))

    def _expire(self):
        now = self.clock()
        for identity in list(self.recorded_at):
            if now - self.recorded_at[identity] > EVENT_TTL:
                self._drop(identity)

    def _drop(self, identity):
        bucket = self.by_session.get((identity[0], identity[1]))
        event = bucket.get(identity) if bucket is not None else None
        if bucket is not None:
            bucket.pop(identity, None)
        self.recorded_at.pop(identity, None)
        self.overflow.add((identity[0], identity[1]))
        self._remember_closed(identity, {"state": "expired", "binding": None, "ack": None,
                                         "fingerprint": _fingerprint(event) if event is not None else None})

    # ---- presentation ledger --------------------------------------------
    def present(self, epoch, session, skillInstanceId, terminalId, conversationSession, player, deliveryId):
        """Fallback-only presentation (no provider). Kept for a Daemon without an LLM configured."""
        state, payload = self.present_begin(epoch, session, skillInstanceId, terminalId,
                                             conversationSession, player, deliveryId)
        if state == "existing":
            return payload
        if state == "generating":
            return {"say": payload, "variantId": "fallback", "mode": "fallback"}
        return self.present_finish(epoch, session, skillInstanceId, terminalId, conversationSession, player,
                                   deliveryId, {"say": payload["__fallback__"], "variantId": "fallback", "mode": "fallback"})

    def present_begin(self, epoch, session, skillInstanceId, terminalId, conversationSession, player, deliveryId):
        """Reserves the presentation slot. Returns ("existing", result) | ("generating", fallback) |
        ("owner", event). The provider must be called by the owner outside this lock."""
        identity = (epoch, session, skillInstanceId, terminalId)
        binding = (conversationSession, player, deliveryId)
        with self.lock:
            entry = self.presentations.get(identity)
            if entry is not None:
                if entry["binding"] != binding:
                    raise TerminalError("binding_conflict")
                if entry["state"] == "generating":
                    return "generating", entry.get("fallback")
                return "existing", self._presentation_result(entry)
            if identity in self.closed:
                raise TerminalError("terminal_gone")
            self._reject_same_skill(identity)
            event = self.by_session.get((epoch, session), {}).get(identity)
            if event is None:
                raise TerminalError("unknown_terminal")
            from terminal_presentation import render_fallback
            fallback = render_fallback(event)
            self.presentations[identity] = {"binding": binding, "state": "generating", "say": None,
                                            "variantId": None, "mode": None, "ack": None,
                                            "fingerprint": _fingerprint(event), "fallback": fallback}
            self.presentations.move_to_end(identity)
            self._bound_presentations()
            result = copy.deepcopy(event)
            result["__fallback__"] = fallback
            return "owner", result

    def present_finish(self, epoch, session, skillInstanceId, terminalId, conversationSession, player,
                       deliveryId, result):
        """Stores the chosen presentation once. Idempotent; a concurrent finisher returns the stored one."""
        identity = (epoch, session, skillInstanceId, terminalId)
        binding = (conversationSession, player, deliveryId)
        with self.lock:
            entry = self.presentations.get(identity)
            if entry is None:
                if identity in self.closed:
                    return {"say": result["say"], "variantId": result["variantId"], "mode": result["mode"]}
                raise TerminalError("unknown_terminal")
            if entry["binding"] != binding:
                raise TerminalError("binding_conflict")
            if entry["state"] == "generating":
                entry["state"] = "ready"
                entry["say"] = result["say"]
                entry["variantId"] = result["variantId"]
                entry["mode"] = result["mode"]
            return self._presentation_result(entry)

    def deliver(self, epoch, session, skillInstanceId, terminalId, conversationSession, player,
                deliveryId, outcome, variant_id):
        identity = (epoch, session, skillInstanceId, terminalId)
        binding = (conversationSession, player, deliveryId)
        with self.lock:
            entry = self.presentations.get(identity)
            if entry is None:
                closed = self.closed.get(identity)
                if closed is None:
                    raise TerminalError("unknown_terminal")
                if closed["binding"] is not None and closed["binding"] != binding:
                    raise TerminalError("binding_conflict")
                if closed["state"] in ("delivered", "suppressed"):
                    if closed["ack"] == (outcome, variant_id):
                        return   # idempotent duplicate ACK
                    raise TerminalError("ack_conflict")
                # An evicted/expired entry: accept this ACK as the close.
                closed["state"] = "delivered" if outcome == "displayed" else "suppressed"
                closed["ack"] = (outcome, variant_id)
                return
            if entry["binding"] != binding:
                raise TerminalError("binding_conflict")
            if entry["state"] in ("delivered", "suppressed"):
                if entry["ack"] == (outcome, variant_id):
                    return
                raise TerminalError("ack_conflict")
            entry["state"] = "delivered" if outcome == "displayed" else "suppressed"
            entry["ack"] = (outcome, variant_id)
            self._close_entry(identity, entry)

    def _close_entry(self, identity, entry):
        # Move to the bounded closed ledger (dropping the heavy say) and close the outbox, so an
        # ACKed terminal is never returned again by a later poll, even from afterSequence=0.
        self._remember_closed(identity, {"state": entry["state"], "binding": entry["binding"],
                                         "ack": entry["ack"], "fingerprint": entry["fingerprint"]})
        self.presentations.pop(identity, None)
        bucket = self.by_session.get((identity[0], identity[1]))
        if bucket is not None:
            bucket.pop(identity, None)
        self.recorded_at.pop(identity, None)

    def _remember_closed(self, identity, record):
        self.closed[identity] = record
        self.closed.move_to_end(identity)
        while len(self.closed) > MAX_CLOSED:
            self.closed.popitem(last=False)
        # A compact digest ledger outlives the exact closed record, so a plain LRU eviction of the
        # heavy record never turns an already-closed identity back into an "unseen" one.
        self.closed_digest[_identity_digest(identity)] = record["fingerprint"]
        self.closed_digest.move_to_end(_identity_digest(identity))
        while len(self.closed_digest) > MAX_CLOSED_DIGESTS:
            self.closed_digest.popitem(last=False)

    def _bound_presentations(self):
        while len(self.presentations) > MAX_PRESENTATIONS:
            identity = next(iter(self.presentations))
            entry = self.presentations[identity]
            self._remember_closed(identity, {"state": "expired", "binding": entry["binding"],
                                             "ack": None, "fingerprint": entry["fingerprint"]})
            self.presentations.pop(identity, None)

    def _reject_same_skill(self, identity):
        skill_prefix = identity[:3]
        for other in list(self.presentations) + list(self.closed):
            if other[:3] == skill_prefix and other != identity:
                raise TerminalError("terminal_conflict")

    @staticmethod
    def _presentation_result(entry):
        return {"say": entry["say"], "variantId": entry["variantId"], "mode": entry["mode"]}
