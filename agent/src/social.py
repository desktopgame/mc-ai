"""Local conversation boundary. No tactical provider or game actions are invoked here."""
import json
import logging
import os
import re
import threading
import time
import unicodedata
from collections import OrderedDict
from pathlib import Path
from typing import Protocol
from urllib import request, error, parse
from context_budget import ContextBudget

LOG = logging.getLogger("mcai.social")
DEFAULT_PERSONA = (
    "あなたはMinecraftでプレイヤーと過ごすCompanionです。親しみやすく落ち着いた日本語で、"
    "通常は1～3文、160文字以内で返答してください。返答本文だけを出力し、思考過程は出力しません。"
    "実際のゲーム状態は与えられていません。世界を観測したり操作を完了したと偽らないでください。"
)
INTENTS = ("none", "follow_owner", "stop", "look_at_owner", "pickup_item", "deposit_items")
INTENT_INSTRUCTIONS = (
    "返答は指定のJSONだけを出力してください。replyは短い日本語の返答、intentは最後のuser発言だけから選びます。"
    "過去の会話にある依頼を再実行してはいけません。現在、実行できる目的は所有者への追従、停止、所有者への注視、"
    "近くに落ちているアイテムの拾得、持っているものの受け渡しだけです。"
    "直接あなたへ頼んでいる明確な現在の依頼なら、ついてきて→follow_owner、止まって→stop、こっちを見て→look_at_owner、"
    "拾って・落ちてるもの拾って→pickup_item、渡して・ちょうだい・持ってるもの全部渡して→deposit_items。"
    "拾得は最も近い落下物だけが対象で、種類を選べません。受け渡しは持ち物すべてが対象で、品物を選べません。"
    "特定の品物を指定した依頼はnoneにして、近くの落ちているものなら拾える・持ち物はすべてまとめて渡せると説明してください。"
    "それ以外はnone。雑談、否定、引用、翻訳、質問、条件付き・仮定の話、第三者への指示、複数の操作の依頼、"
    "あそこへ行く・採掘や設置等の未対応操作、目的が曖昧な表現はnoneにして説明または確認してください。"
    "例: こんにちは→none、止まらないで→none（今の動作を変えないと返答し、停止の確認をしない）、『ついてきて』という意味は？→none、"
    "敵が来たら止まって→none、あの木を見て→none、鉄を取ってきて→none（採掘は未対応）、ダイヤだけ拾って→none（種類の指定は不可）、"
    "そこに落ちてるの拾って→pickup_item、持ってるもの渡して→deposit_items、砂だけ渡して→none（種類の指定は不可）。"
    "intentがある場合、replyは依頼を受け付ける返答にし、実行開始・成功を断定しないでください。"
    "実行可否は後の別処理で決まります。自由形式のコマンドや操作パラメーターは出力しないでください。"
)
SOCIAL_SCHEMA = {"type": "object", "additionalProperties": False,
                 "properties": {"reply": {"type": "string"}, "intent": {"type": "string", "enum": list(INTENTS)}},
                 "required": ["reply", "intent"]}
SOCIAL_RESPONSE_FORMAT = {"type": "json_schema", "json_schema": {
    "name": "social_turn", "strict": True, "schema": SOCIAL_SCHEMA}}

# Terminal notification is a *record of finished work*, not current world state. The model only picks
# one of the pre-built fact-complete candidates; it never writes text, numbers or intents.
TERMINAL_INSTRUCTIONS = (
    "ここで渡すのは終了済みの作業の確定記録(skill_terminal)だけです。現在の世界状態ではなく、"
    "すでに終わった作業の記録です。あなたは提示された候補から、事実を一切変えずに、会話の流れに最も合う"
    "1つを選ぶだけです。候補にない文・数値・理由・推測を追加しないでください。今後の作業や再試行を提案せず、"
    "reply/intent/actionは出力しません。出力は指定のJSONだけにし、variantIdは提示した候補IDのいずれかにします。"
)
TERMINAL_SCHEMA = {"type": "object", "additionalProperties": False,
                   "properties": {"variantId": {"type": "string"}}, "required": ["variantId"]}
TERMINAL_RESPONSE_FORMAT = {"type": "json_schema", "json_schema": {
    "name": "terminal_variant", "strict": True, "schema": TERMINAL_SCHEMA}}


def allows_intent(text):
    """Conservative additional guard; the model still classifies direct requests and targets."""
    blocked = ("「", "」", "『", "』", '"', "“", "”", "`", "もし", "たら", "なら", "場合", "ときは", "時は",
               "例えば", "たとえば", "って言", "と言", "と書", "意味", "翻訳", "教えて", "できる", "できます",
               # "ないで" covers the negative imperative for any verb, including ones added later.
               "ないで", "しない", "来ない", "こない", "こなく", "見ない", "向かない", "止まらない", "止まらず",
               "やめない", "拾わない", "拾わず",
               "きてから", "してから", "その後", "それから", "そして", "まず")
    return not any(word in text for word in blocked) and not re.search(r"\b(if|not|never|don't|quote|translate)\b", text, re.I)


def keep_current_request(text):
    value = unicodedata.normalize("NFKC", text).strip().rstrip("!。 ")
    return value in ("止まらないで", "止まらないでください", "停止しないで", "停止しないでください",
                     "そのまま続けて", "そのまま続けてください")


def validate_social_turn(value):
    if not isinstance(value, dict) or set(value) != {"reply", "intent"}:
        raise SocialError("invalid_social_turn")
    if type(value["intent"]) is not str or value["intent"] not in INTENTS:
        raise SocialError("invalid_intent")
    return {"reply": validate_reply(value["reply"]), "intent": value["intent"]}


class SocialError(Exception):
    pass


class SocialProvider(Protocol):
    def reply(self, messages: list[dict]) -> str: ...
    def reply_with_intent(self, messages: list[dict]) -> dict: ...


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
    def __init__(self, config, *, token_counter=None):
        self.budget = ContextBudget(config, social=True, counter=token_counter)
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
        self.max_tokens = self.budget.max_output_tokens
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
        return self._reply(messages, False)

    def reply_with_intent(self, messages):
        return self._reply(messages, True)

    def _reply(self, messages, with_intent):
        response_format = SOCIAL_RESPONSE_FORMAT if with_intent else None
        self.budget.require(messages, response_format)
        payload = {"model": self.model, "messages": messages, "stream": False,
                   "temperature": 0 if with_intent else 0.7, "max_tokens": self.max_tokens}
        if with_intent:
            payload["response_format"] = response_format
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
            content = choice["message"]["content"]
            result = validate_social_turn(json.loads(content)) if with_intent else validate_reply(content)
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


    def select_terminal(self, messages, variant_ids):
        """Presentation-only call: the model must return one of the pre-built candidate IDs.

        Same provider/settings as chat, but a dedicated strict schema and a short deadline. Any
        deviation (free text, unknown ID, extra key, timeout) raises SocialError so the caller can
        fall back to the fixed renderer.
        """
        schema = {"type": "object", "additionalProperties": False,
                  "properties": {"variantId": {"type": "string", "enum": list(variant_ids)}},
                  "required": ["variantId"]}
        response_format = {"type": "json_schema", "json_schema": {
            "name": "terminal_variant", "strict": True, "schema": schema}}
        content = self._post(messages, response_format, min(self.timeout, 8.0), min(64, self.max_tokens))
        value = json.loads(content)
        if not isinstance(value, dict) or set(value) != {"variantId"} or value["variantId"] not in variant_ids:
            raise SocialError("invalid_variant")
        return value["variantId"]

    def _post(self, messages, response_format, timeout, max_tokens):
        self.budget.require(messages, response_format)
        payload = {"model": self.model, "messages": messages, "stream": False,
                   "temperature": 0, "max_tokens": max_tokens}
        if response_format is not None:
            payload["response_format"] = response_format
        if self.reasoning_effort is not None:
            payload["reasoning_effort"] = self.reasoning_effort
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        headers = {"Content-Type": "application/json"}
        if self.api_key:
            headers["Authorization"] = "Bearer " + self.api_key
        started = time.monotonic()
        try:
            req = request.Request(self.base_url + "/chat/completions", body, headers, method="POST")
            with self.opener.open(req, timeout=timeout) as response:
                raw = response.read(65537)
            if len(raw) > 65536:
                raise SocialError("oversized_output")
            parsed = json.loads(raw)
            choice = parsed["choices"][0]
            if choice.get("finish_reason") != "stop" or choice["message"].get("tool_calls"):
                raise SocialError("incomplete_or_tool_output")
            LOG.info("provider=local kind=terminal duration_ms=%d output_bytes=%d", (time.monotonic() - started) * 1000, len(raw))
            return choice["message"]["content"]
        except SocialError:
            raise
        except (TimeoutError, error.URLError, OSError) as exc:
            LOG.warning("provider=local kind=terminal failure=%s duration_ms=%d", type(exc).__name__, (time.monotonic() - started) * 1000)
            raise SocialError("provider_unavailable_or_timeout") from None
        except (ValueError, KeyError, IndexError, TypeError):
            raise SocialError("malformed_output") from None


class SocialBrain:
    def __init__(self, provider: SocialProvider, persona=DEFAULT_PERSONA, *, budget=None):
        if not isinstance(persona, str) or not 1 <= len(persona) <= 2000:
            raise ValueError("persona must contain 1 to 2000 characters")
        self.provider = provider
        self.budget = budget or getattr(provider, "budget", None) or ContextBudget(social=True)
        self.persona = persona
        self.histories = OrderedDict()
        self.lock = threading.Lock()

    def chat(self, session, text, with_intent=False):
        # No unbounded inference queue; retain history only after a valid completed response.
        if not self.lock.acquire(blocking=False):
            raise SocialError("busy")
        try:
            history = self.histories.get(session, [])
            instructions = self.persona + ("\n" + INTENT_INSTRUCTIONS if with_intent else
                                           "\nこの返答経路では会話のみで、操作を依頼する場合は !agent do follow / look / stop を案内してください。")
            user = {"role": "user", "content": text}
            continuation = with_intent and keep_current_request(text)
            if continuation:
                history = self.budget.trim_history(history)
            else:
                messages, history = self.budget.prepare({"role": "system", "content": instructions}, history, user,
                                                       SOCIAL_RESPONSE_FORMAT if with_intent else None)
            if with_intent:
                # No world state is available here: acknowledge unchanged behavior, never claim a task.
                result = ({"reply": "わかった。今の動作は変えないよ。", "intent": "none"}
                          if continuation else validate_social_turn(self.provider.reply_with_intent(messages)))
                if result["intent"] != "none" and not allows_intent(text):
                    result = {"reply": "その表現では操作を変更しません。今してほしい操作を、ひとつだけ直接依頼してください。", "intent": "none"}
                reply = result["reply"]
            else:
                reply = validate_reply(self.provider.reply(messages))
            recent = self.budget.trim_history(history + [user, {"role": "assistant", "content": reply}])
            self.histories[session] = recent
            self.histories.move_to_end(session)
            while len(self.histories) > 32:
                self.histories.popitem(last=False)
            return result if with_intent else reply
        finally:
            self.lock.release()

    def present_terminal(self, session, event):
        """Choose one fact-complete candidate for a finalized terminal event using the same persona,
        provider, history budget and lock as normal chat. Never writes free text; never touches the
        conversation history (that happens only on a real delivery ACK). Raises SocialError to fall
        back to the fixed renderer."""
        from terminal_presentation import render_candidates
        if not self.lock.acquire(blocking=False):
            raise SocialError("busy")
        try:
            select = getattr(self.provider, "select_terminal", None)
            if select is None:
                raise SocialError("presentation_unsupported")
            candidates = render_candidates(event)
            variant_ids = [candidate["variantId"] for candidate in candidates]
            # Transport details (session/epoch/UUID) are deliberately not sent to the model.
            fact = {"type": event["type"], "target": event["target"], "status": event["status"],
                    "reason": event["reason"], "progress": event["progress"]}
            user = {"role": "user", "content": "確定記録: " + json.dumps(fact, ensure_ascii=False)
                    + "\n候補: " + json.dumps(candidates, ensure_ascii=False)
                    + "\nこの候補から variantId を1つ選んでください。"}
            # The same conversation identity/history as normal chat is read, but never written here:
            # history registration happens only after a real displayed ACK (Phase 6).
            history = self.histories.get(session, [])
            messages, _ = self.budget.prepare(
                {"role": "system", "content": self.persona + "\n" + TERMINAL_INSTRUCTIONS},
                history, user, TERMINAL_RESPONSE_FORMAT)
            variant = select(messages, variant_ids)
            say = next(candidate["say"] for candidate in candidates if candidate["variantId"] == variant)
            return {"say": say, "variantId": variant, "mode": "social"}
        finally:
            self.lock.release()

    def forget(self, session):
        if not self.lock.acquire(blocking=False):
            raise SocialError("busy")
        try:
            self.histories.pop(session, None)
        finally:
            self.lock.release()
