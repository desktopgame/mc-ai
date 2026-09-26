"""One-shot tactical decisions. This module has no access to SocialBrain or its history."""
import copy
import json
import logging
import math
import os
import threading
import time
from pathlib import Path
from typing import Protocol
from urllib import request, error, parse

LOG = logging.getLogger("mcai.decision")
GOALS = {"follow_owner": "follow", "stop": "stop", "look_at_owner": "look"}
ACTIONS = ("follow", "stop", "look")
REASONS = ("goal_follow", "goal_stop", "goal_look", "owner_near", "low_health", "owner_out_of_range", "unavailable_action")
SYSTEM = (
    "Choose exactly one action from availableActions using only the supplied goal and state. "
    "The player is always the anonymous alias owner. Do not request or produce reasoning text. "
    "If health <= 6, choose stop with low_health. If goal is stop, choose stop with goal_stop. "
    "For follow_owner: if owner is within 2 blocks choose follow owner/owner_near, "
    "keeping the follow task active so movement resumes when the owner moves away; "
    "if farther than 32 blocks choose stop/owner_out_of_range; otherwise follow owner/goal_follow. "
    "For look_at_owner choose look owner/goal_look. If the desired action is unavailable, "
    "choose stop/unavailable_action if available. Output only the specified JSON object."
)
DECISION_SCHEMA = {
    "type": "object", "additionalProperties": False,
    "properties": {
        "decision": {"anyOf": [
            {"type": "object", "additionalProperties": False,
             "properties": {"action": {"type": "string", "enum": ["stop"]}}, "required": ["action"]},
            {"type": "object", "additionalProperties": False,
             "properties": {"action": {"type": "string", "enum": ["follow", "look"]},
                            "target": {"type": "string", "enum": ["owner"]}}, "required": ["action", "target"]}
        ]},
        "reasonCode": {"type": "string", "enum": list(REASONS)}
    }, "required": ["decision", "reasonCode"]
}


class DecisionError(Exception):
    pass


class DecisionProvider(Protocol):
    def decide(self, payload: dict) -> dict: ...


def number(value, minimum, maximum):
    if type(value) not in (int, float) or not minimum <= value <= maximum or not math.isfinite(value):
        raise ValueError("invalid_numeric_state")
    return value


def position(value):
    if not isinstance(value, list) or len(value) != 3:
        raise ValueError("invalid_position")
    return [number(value[0], -30000000, 30000000), number(value[1], -2048, 2048), number(value[2], -30000000, 30000000)]


def sanitize(payload):
    """Reconstruct an allowlisted payload; never forward caller dictionaries or free-form text."""
    if not isinstance(payload, dict) or type(payload.get("version")) is not int or payload["version"] != 1:
        raise ValueError("unsupported_version")
    goal = payload.get("goal")
    if not isinstance(goal, dict) or not isinstance(goal.get("type"), str) or goal["type"] not in GOALS:
        raise ValueError("unsupported_goal")
    state = payload.get("state")
    if not isinstance(state, dict) or not isinstance(state.get("companion"), dict) or not isinstance(state.get("owner"), dict):
        raise ValueError("invalid_state")
    actions = payload.get("availableActions")
    if not isinstance(actions, list) or not actions or any(type(a) is not str or a not in ACTIONS for a in actions):
        raise ValueError("invalid_available_actions")
    if len(set(actions)) != len(actions):
        raise ValueError("duplicate_available_actions")
    return {"goal": {"type": goal["type"]},
            "state": {"companion": {"health": number(state["companion"].get("health"), 0, 20),
                                     "position": position(state["companion"].get("position"))},
                      "owner": {"position": position(state["owner"].get("position"))}},
            "availableActions": list(actions)}


def distance_squared(payload):
    state = payload["state"]
    return sum((a - b) ** 2 for a, b in zip(state["companion"]["position"], state["owner"]["position"]))


def validate_decision(value, payload):
    if not isinstance(value, dict) or set(value) != {"decision", "reasonCode"}:
        raise DecisionError("invalid_output")
    action = value["decision"]
    if not isinstance(action, dict) or type(action.get("action")) is not str or action["action"] not in payload["availableActions"]:
        raise DecisionError("unknown_or_unavailable_action")
    kind = action["action"]
    if kind == "stop":
        if set(action) != {"action"}:
            raise DecisionError("invalid_parameters")
    elif set(action) != {"action", "target"} or action.get("target") != "owner":
        raise DecisionError("invalid_target")
    if type(value["reasonCode"]) is not str or value["reasonCode"] not in REASONS:
        raise DecisionError("invalid_reason_code")
    goal = payload["goal"]["type"]
    if kind != "stop" and (payload["state"]["companion"]["health"] <= 6 or kind != GOALS[goal]):
        raise DecisionError("unsafe_or_mismatched_action")
    if kind == "follow" and not 0 <= distance_squared(payload) <= 1024:
        raise DecisionError("unsafe_follow_distance")
    return copy.deepcopy(value)


class MockDecisionProvider:
    def decide(self, payload):
        goal = payload["goal"]["type"]
        action = GOALS[goal]
        reason = {"follow": "goal_follow", "stop": "goal_stop", "look": "goal_look"}[action]
        if payload["state"]["companion"]["health"] <= 6:
            action, reason = "stop", "low_health"
        elif action == "follow" and distance_squared(payload) <= 4:
            reason = "owner_near"
        elif action == "follow" and distance_squared(payload) > 1024:
            action, reason = "stop", "owner_out_of_range"
        if action not in payload["availableActions"]:
            action, reason = "stop", "unavailable_action"
        decision = {"action": action}
        if action != "stop":
            decision["target"] = "owner"
        return {"decision": decision, "reasonCode": reason}


class NoRedirect(request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class LocalDecisionProvider:
    """JSON-schema-capable local OpenAI-compatible endpoint; no history and no SocialProvider reuse."""
    def __init__(self, config):
        self.base_url = config.get("base_url", "http://127.0.0.1:1234/v1").rstrip("/")
        url = parse.urlsplit(self.base_url)
        if url.scheme != "http" or url.hostname not in ("localhost", "127.0.0.1", "::1") or url.username or url.password or url.query or url.fragment:
            raise ValueError("decision base_url must be loopback HTTP")
        self.model = config.get("model")
        if not isinstance(self.model, str) or not self.model.strip():
            raise ValueError("decision model is required")
        self.timeout = float(config.get("timeout_seconds", 15))
        if not 1 <= self.timeout <= 45:
            raise ValueError("decision timeout must be 1 to 45 seconds")
        self.api_key = os.environ.get(config.get("api_key_env", "MCAI_DECISION_API_KEY"), "")
        if not self.api_key and config.get("api_key_file"):
            self.api_key = Path(config["api_key_file"]).read_text(encoding="utf-8-sig").strip()
        self.opener = request.build_opener(request.ProxyHandler({}), NoRedirect())

    def decide(self, payload):
        body = json.dumps({"model": self.model, "stream": False, "temperature": 0,
                           "max_tokens": 256, "reasoning_effort": "none",
                           "messages": [{"role": "system", "content": SYSTEM},
                                        {"role": "user", "content": json.dumps(payload, allow_nan=False)}],
                           "response_format": {"type": "json_schema", "json_schema": {
                               "name": "tactical_decision", "strict": True, "schema": DECISION_SCHEMA}}},
                          allow_nan=False).encode("utf-8")
        headers = {"Content-Type": "application/json"}
        if self.api_key:
            headers["Authorization"] = "Bearer " + self.api_key
        started = time.monotonic()
        try:
            req = request.Request(self.base_url + "/chat/completions", body, headers, method="POST")
            with self.opener.open(req, timeout=self.timeout) as response:
                raw = response.read(65537)
            if len(raw) > 65536:
                raise DecisionError("oversized_output")
            result = json.loads(raw)
            choice = result["choices"][0]
            if choice.get("finish_reason") != "stop" or choice["message"].get("tool_calls"):
                raise DecisionError("incomplete_or_tool_output")
            decision = json.loads(choice["message"]["content"])
            LOG.info("provider=local model=%s latency_ms=%d input_bytes=%d output_bytes=%d",
                     self.model, (time.monotonic() - started) * 1000, len(body), len(raw))
            return decision
        except DecisionError:
            raise
        except (TimeoutError, error.URLError, OSError):
            raise DecisionError("provider_unavailable_or_timeout") from None
        except (ValueError, KeyError, IndexError, TypeError, AttributeError):
            raise DecisionError("malformed_output") from None


class DecisionService:
    def __init__(self, provider: DecisionProvider):
        self.provider = provider
        self.lock = threading.Lock()

    def decide(self, request_payload):
        payload = sanitize(request_payload)
        if not self.lock.acquire(blocking=False):
            raise DecisionError("busy")
        started = time.monotonic()
        try:
            decision = validate_decision(self.provider.decide(copy.deepcopy(payload)), payload)
            LOG.info("decision provider=%s action=%s reason=%s latency_ms=%d",
                     type(self.provider).__name__, decision["decision"]["action"], decision["reasonCode"],
                     (time.monotonic() - started) * 1000)
            return {"version": 1, **decision, "executed": False}
        finally:
            self.lock.release()
