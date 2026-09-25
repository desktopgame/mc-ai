"""Local conversation boundary. No tactical provider or game actions are invoked here."""
import json
import logging
import os
import threading
import time
from collections import OrderedDict
from pathlib import Path
from typing import Protocol
from urllib import request, error, parse

LOG = logging.getLogger("mcai.social")
DEFAULT_PERSONA = (
    "あなたはMinecraftでプレイヤーと過ごすCompanionです。親しみやすく落ち着いた日本語で、"
    "通常は1～3文、160文字以内で返答してください。返答本文だけを出力し、思考過程は出力しません。"
    "この段階では会話だけができます。世界を観測したりゲーム操作を実行したと偽らないでください。"
    "移動の依頼には手動コマンド !agent follow / !agent stop を案内できます。"
)


class SocialError(Exception):
    pass


class SocialProvider(Protocol):
    def reply(self, messages: list[dict]) -> str: ...


def validate_reply(value):
    if not isinstance(value, str) or not value.strip():
        raise SocialError("invalid_output")
    value = value.strip()
    if len(value.encode("utf-16-le")) // 2 > 512 or "<think" in value.lower():
        raise SocialError("invalid_output")
    if any((ord(c) < 32 and c not in "\n\t") or c == "\u00a7" for c in value):
        raise SocialError("invalid_output")
    return value.replace("\n", " ").replace("\t", " ")


class NoRedirect(request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class LocalSocialProvider:
    def __init__(self, config):
        self.base_url = config.get("base_url", "http://127.0.0.1:1234/v1").rstrip("/")
        url = parse.urlsplit(self.base_url)
        if url.scheme != "http" or url.hostname not in ("127.0.0.1", "localhost", "::1") or url.username or url.password or url.query or url.fragment:
            raise ValueError("Phase 3 social base_url must be a loopback HTTP URL")
        self.model = config["model"]
        if not isinstance(self.model, str) or not self.model.strip():
            raise ValueError("model is required")
        self.timeout = float(config.get("timeout_seconds", 30))
        if not 1 <= self.timeout <= 45:
            raise ValueError("timeout_seconds must be between 1 and 45")
        self.max_tokens = int(config.get("max_tokens", 256))
        self.reasoning_effort = config.get("reasoning_effort", "none")
        if self.reasoning_effort not in (None, "none", "low", "medium", "high"):
            raise ValueError("invalid reasoning_effort")
        if not 1 <= self.max_tokens <= 1024:
            raise ValueError("max_tokens must be between 1 and 1024")
        self.api_key = os.environ.get(config.get("api_key_env", "MCAI_SOCIAL_API_KEY"), "")
        if not self.api_key and config.get("api_key_file"):
            self.api_key = Path(config["api_key_file"]).read_text(encoding="utf-8-sig").strip()
        self.opener = request.build_opener(request.ProxyHandler({}), NoRedirect())

    def reply(self, messages):
        payload = {"model": self.model, "messages": messages, "stream": False,
                   "temperature": 0.7, "max_tokens": self.max_tokens}
        if self.reasoning_effort is not None:
            payload["reasoning_effort"] = self.reasoning_effort
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        headers = {"Content-Type": "application/json"}
        if self.api_key:
            headers["Authorization"] = "Bearer " + self.api_key
        started = time.monotonic()
        try:
            req = request.Request(self.base_url + "/chat/completions", body, headers, method="POST")
            with self.opener.open(req, timeout=self.timeout) as response:
                raw = response.read(65537)
            if len(raw) > 65536:
                raise SocialError("oversized_output")
            parsed = json.loads(raw)
            choice = parsed["choices"][0]
            if choice.get("finish_reason") != "stop" or choice["message"].get("tool_calls"):
                raise SocialError("incomplete_or_tool_output")
            result = validate_reply(choice["message"]["content"])
            usage = parsed.get("usage", {})
            # Size, latency and token counts only. Never dump content or credentials.
            LOG.info("provider=local model=%s duration_ms=%d input_bytes=%d output_bytes=%d prompt_tokens=%s completion_tokens=%s",
                     self.model, (time.monotonic() - started) * 1000, len(body), len(raw),
                     usage.get("prompt_tokens"), usage.get("completion_tokens"))
            return result
        except SocialError:
            raise
        except (TimeoutError, error.URLError, OSError) as exc:
            LOG.warning("provider=local failure=%s duration_ms=%d", type(exc).__name__, (time.monotonic() - started) * 1000)
            raise SocialError("provider_unavailable_or_timeout") from None
        except (ValueError, KeyError, IndexError, TypeError):
            raise SocialError("malformed_output") from None


class SocialBrain:
    def __init__(self, provider: SocialProvider, persona=DEFAULT_PERSONA):
        if not isinstance(persona, str) or not 1 <= len(persona) <= 2000:
            raise ValueError("persona must contain 1 to 2000 characters")
        self.provider = provider
        self.persona = persona
        self.histories = OrderedDict()
        self.lock = threading.Lock()

    def chat(self, session, text):
        # No unbounded inference queue; retain history only after a valid completed response.
        if not self.lock.acquire(blocking=False):
            raise SocialError("busy")
        try:
            history = self.histories.get(session, [])
            messages = [{"role": "system", "content": self.persona}] + history + [{"role": "user", "content": text}]
            reply = validate_reply(self.provider.reply(messages))
            recent = (history + [{"role": "user", "content": text}, {"role": "assistant", "content": reply}])[-12:]
            while len(recent) > 2 and sum(len(item["content"]) for item in recent) > 4000:
                recent = recent[2:]
            self.histories[session] = recent
            self.histories.move_to_end(session)
            while len(self.histories) > 32:
                self.histories.popitem(last=False)
            return reply
        finally:
            self.lock.release()

    def forget(self, session):
        if not self.lock.acquire(blocking=False):
            raise SocialError("busy")
        try:
            self.histories.pop(session, None)
        finally:
            self.lock.release()
