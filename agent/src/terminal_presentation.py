"""Static presentation descriptors, reason semantics and the fixed terminal renderer.

Pure: no Minecraft, HTTP, provider or social dependencies. The fixed renderer here must produce the
same strings as the Forge `TerminalPresentation.java` renderer; `protocol/fixtures/skill-terminal-
fallback.json` is the shared contract. The Daemon owns the terminal *fact*; this module only turns an
already-finalized terminal event into display text.
"""

# Registry name -> short Japanese display label. Unknown names fall back to the verified registry name.
TARGET_LABELS = {
    "minecraft:log": "原木",
    "minecraft:log2": "原木",
    "minecraft:cobblestone": "丸石",
    "minecraft:planks": "木材",
    "minecraft:stick": "棒",
    "minecraft:iron_ingot": "鉄インゴット",
    "minecraft:stone": "石",
    "minecraft:coal_ore": "石炭鉱石",
    "minecraft:iron_ore": "鉄鉱石",
    "minecraft:gold_ore": "金鉱石",
    "minecraft:diamond_ore": "ダイヤ鉱石",
    "minecraft:dirt": "土",
    "minecraft:sand": "砂",
    "minecraft:gravel": "砂利",
}

# Skill type -> static descriptor. metrics are (progressKey, label, unit) in display order; the first
# metric is the success metric measured against `requested`.
DESCRIPTORS = {
    "collect_drop": {"task": "回収", "field": "item", "metrics": (("acquired", "回収", "個"),)},
    "mine": {"task": "採掘", "field": "block", "metrics": (("mined", "採掘", "ブロック"),)},
    "collect_block": {"task": "回収", "field": "block",
                      "metrics": (("acquired", "回収", "個"), ("mined", "採掘", "ブロック"))},
}

# reason -> one short allowed meaning added to *failed* output. None means "no extra sentence".
# Unknown reasons are never summarised: the raw enum is shown instead.
REASON_MEANINGS = {
    "no_block_in_range": "今回確認した範囲で対象を確認できませんでした。",
    "no_item_in_range": "今回確認した範囲で対象を確認できませんでした。",
    "drop_unavailable": "確認した範囲で回収対象を見つけられませんでした。",
    "blocked": "操作が遮られて完了できませんでした。",
    "path_not_found": "今回は到達経路を確保できませんでした。",
    "inventory_full": "回収先の収納が足りませんでした。",
    "tool_unavailable": "必要な道具を利用できませんでした。",
    "unsafe_state": "安全条件を満たせませんでした。",
    "owner_unavailable": "必要な相手を利用できませんでした。",
    "companion_unavailable": "Companionを利用できませんでした。",
    "stale_state": "新しい状態を確認できませんでした。",
    "expired": "実行期限内に完了できませんでした。",
    "disconnected": "実行側の接続が失われました。",
    "retry_exhausted": "試行上限に達しました。",
    "action_failed": "操作を完了できませんでした。",
    "stopped": None,
    "replaced": None,
    "completed": None,
}

STATUS_HEAD = {"completed": "完了。", "failed": "失敗[{reason}]。", "cancelled": "取消[{reason}]。"}
MAX_UTF16 = 512


def _utf16_length(text):
    return len(text.encode("utf-16-le")) // 2


def target_name(event):
    field = DESCRIPTORS[event["type"]]["field"]
    name = event["target"][field]
    return TARGET_LABELS.get(name, name)


def _status_head(status, reason):
    return STATUS_HEAD[status].format(reason=reason)


def _metric_parts(event, completed):
    progress = event["progress"]
    requested = progress["requested"]
    metrics = DESCRIPTORS[event["type"]]["metrics"]
    parts = []
    for index, (key, label, unit) in enumerate(metrics):
        value = progress[key]
        if completed and index == 0:
            parts.append("%sを%s/%s%s%s" % (target_name(event), value, requested, unit, label))
        elif index == 0:
            parts.append("%s%s/%s%s" % (label, value, requested, unit))
        else:
            parts.append("%s%s%s" % (label, value, unit))
    return parts


def _body(event):
    status, reason = event["status"], event["reason"]
    progress = event["progress"]
    parts = _metric_parts(event, status == "completed")
    if status == "completed":
        return "、".join(parts) + "。"
    body = ("確定分は" if not progress["complete"] else "") + "、".join(parts) + "。"
    if not progress["complete"]:
        body += "未確定の操作があります。"
    if status == "failed" and reason in REASON_MEANINGS and REASON_MEANINGS[reason]:
        body += REASON_MEANINGS[reason]
    elif reason not in REASON_MEANINGS:
        body += "終了理由: %s。" % reason
    return body


def render_fallback(event):
    """The fixed, LLM-free rendering. Bounded to 512 UTF-16 units without dropping numbers/notes."""
    text = "%s%s: %s%s" % (target_name(event), DESCRIPTORS[event["type"]]["task"],
                           _status_head(event["status"], event["reason"]), _body(event))
    if _utf16_length(text) <= MAX_UTF16:
        return text
    # Compact form keeps every counter and the incomplete note, dropping only decorative wording.
    progress = event["progress"]
    metrics = DESCRIPTORS[event["type"]]["metrics"]
    compact = "".join("%s%s%s%s" % ((label, progress[key], "/" + str(progress["requested"]) if index == 0 else "", unit))
                      for index, (key, label, unit) in enumerate(metrics))
    note = "未確定の操作があります。" if not progress["complete"] else ""
    compact = "%s%s: %s%s。%s" % (target_name(event), DESCRIPTORS[event["type"]]["task"],
                                  _status_head(event["status"], event["reason"]), compact, note)
    return compact[:MAX_UTF16]
