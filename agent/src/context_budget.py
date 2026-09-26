"""Provider-local budgets. The fallback estimates tokens; it is not a model tokenizer."""
import copy
import json
import logging
from typing import Protocol

LOG = logging.getLogger("mcai.budget")


class BudgetExceeded(Exception):
    pass


class TokenCounter(Protocol):
    # An exact adapter must count the model's chat template AND structured-output schema.
    mode: str
    def count(self, messages: list[dict], response_format: dict | None) -> int: ...


class Utf8Estimate:
    mode = "utf8_estimate"

    def count(self, messages, response_format=None):
        payload = {"messages": messages}
        if response_format is not None: payload["response_format"] = response_format
        # One token per serialized UTF-8 byte, plus template overhead. Deliberately conservative
        # for English/Japanese; arbitrary provider templates still cannot be guaranteed.
        return len(json.dumps(payload, ensure_ascii=False, separators=(",", ":"), allow_nan=False).encode("utf-8")) + 64 + 32 * len(messages)


def integer(config, key, default, minimum=1, maximum=2097152):
    value = config.get(key, default)
    if type(value) is not int or not minimum <= value <= maximum:
        raise ValueError("invalid_" + key)
    return value


class ContextBudget:
    def __init__(self, config=None, *, social=False, counter=None):
        config = config or {}
        self.counter = counter or Utf8Estimate()
        self.context_window_tokens = integer(config, "context_window_tokens", 8192)
        legacy = integer(config, "max_tokens", 256, maximum=1024)
        self.max_output_tokens = integer(config, "max_output_tokens", legacy, maximum=1024)
        if "max_tokens" in config and "max_output_tokens" in config and legacy != self.max_output_tokens:
            raise ValueError("conflicting_output_budgets")
        self.safety_margin_tokens = integer(config, "safety_margin_tokens", 512, minimum=0)
        available = self.context_window_tokens - self.max_output_tokens - self.safety_margin_tokens
        if available < 1: raise ValueError("context_has_no_input_capacity")
        self.prompt_budget_tokens = integer(config, "prompt_budget_tokens", min(8192 if social else 4096, available))
        if self.prompt_budget_tokens > available: raise ValueError("prompt_budget_exceeds_context_capacity")
        self.history_budget_tokens = integer(config, "history_budget_tokens", min(4096, self.prompt_budget_tokens) if social else 0, minimum=0)
        if self.history_budget_tokens > self.prompt_budget_tokens: raise ValueError("history_budget_exceeds_prompt_budget")
        if not social and self.history_budget_tokens != 0: raise ValueError("decision_history_not_supported")
        LOG.info("context configured counter=%s context=%d prompt_budget=%d history_budget=%d output=%d margin=%d",
                 self.counter.mode, self.context_window_tokens, self.prompt_budget_tokens,
                 self.history_budget_tokens, self.max_output_tokens, self.safety_margin_tokens)

    def count(self, messages, response_format=None):
        value = self.counter.count(copy.deepcopy(messages), copy.deepcopy(response_format))
        if type(value) is not int or value < 0: raise ValueError("invalid_token_count")
        return value

    def require(self, messages, response_format=None):
        size = self.count(messages, response_format)
        LOG.info("prompt counter=%s tokens=%d budget=%d output_reserved=%d", self.counter.mode,
                 size, self.prompt_budget_tokens, self.max_output_tokens)
        if size > self.prompt_budget_tokens: raise BudgetExceeded("prompt_budget_exceeded")
        return size

    def trim_history(self, history):
        history = copy.deepcopy(history)
        if len(history) % 2 or any(m.get("role") != ("user" if i % 2 == 0 else "assistant") for i, m in enumerate(history)):
            raise ValueError("invalid_history_pairs")
        # Empty history always fits a zero budget. Count history as a standalone message list
        # including conservative framing rather than subtracting two potentially non-additive counts.
        while history and self.count(history) > self.history_budget_tokens:
            history = history[2:]
        return history

    def prepare(self, system, history, user, response_format=None):
        mandatory = [system, user]
        if self.count(mandatory, response_format) > self.prompt_budget_tokens:
            raise BudgetExceeded("mandatory_prompt_exceeds_budget")
        retained = self.trim_history(history)
        while retained and self.count([system] + retained + [user], response_format) > self.prompt_budget_tokens:
            retained = retained[2:]
        messages = [system] + retained + [user]
        self.require(messages, response_format)
        LOG.info("history retained_pairs=%d dropped_pairs=%d counter=%s", len(retained) // 2,
                 (len(history) - len(retained)) // 2, self.counter.mode)
        return copy.deepcopy(messages), retained
