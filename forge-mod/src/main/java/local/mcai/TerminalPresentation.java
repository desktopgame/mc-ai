package local.mcai;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Fixed, LLM-free terminal renderer. Must reproduce {@code terminal_presentation.render_fallback}
 * exactly; {@code protocol/fixtures/skill-terminal-fallback.json} is the shared contract. Pure.
 */
public final class TerminalPresentation {
    private TerminalPresentation() { }

    private static final Map<String, String> LABELS = new HashMap<String, String>();
    static {
        LABELS.put("minecraft:log", "原木"); LABELS.put("minecraft:log2", "原木");
        LABELS.put("minecraft:cobblestone", "丸石"); LABELS.put("minecraft:planks", "木材");
        LABELS.put("minecraft:stick", "棒"); LABELS.put("minecraft:iron_ingot", "鉄インゴット");
        LABELS.put("minecraft:stone", "石"); LABELS.put("minecraft:coal_ore", "石炭鉱石");
        LABELS.put("minecraft:iron_ore", "鉄鉱石"); LABELS.put("minecraft:gold_ore", "金鉱石");
        LABELS.put("minecraft:diamond_ore", "ダイヤ鉱石"); LABELS.put("minecraft:dirt", "土");
        LABELS.put("minecraft:sand", "砂"); LABELS.put("minecraft:gravel", "砂利");
    }

    /** (progressKey, label, unit) in display order, per Skill type. */
    private static final Map<String, List<String[]>> METRICS = new HashMap<String, List<String[]>>();
    static {
        List<String[]> drop = new ArrayList<String[]>();
        drop.add(new String[] {"acquired", "回収", "個"});
        METRICS.put("collect_drop", drop);
        List<String[]> mine = new ArrayList<String[]>();
        mine.add(new String[] {"mined", "採掘", "ブロック"});
        METRICS.put("mine", mine);
        List<String[]> block = new ArrayList<String[]>();
        block.add(new String[] {"acquired", "回収", "個"});
        block.add(new String[] {"mined", "採掘", "ブロック"});
        METRICS.put("collect_block", block);
    }
    private static final Map<String, String> TASKS = new HashMap<String, String>();
    static { TASKS.put("collect_drop", "回収"); TASKS.put("mine", "採掘"); TASKS.put("collect_block", "回収"); }

    private static final Map<String, String> MEANINGS = new HashMap<String, String>();
    static {
        MEANINGS.put("no_block_in_range", "今回確認した範囲で対象を確認できませんでした。");
        MEANINGS.put("no_item_in_range", "今回確認した範囲で対象を確認できませんでした。");
        MEANINGS.put("drop_unavailable", "確認した範囲で回収対象を見つけられませんでした。");
        MEANINGS.put("blocked", "操作が遮られて完了できませんでした。");
        MEANINGS.put("path_not_found", "今回は到達経路を確保できませんでした。");
        MEANINGS.put("inventory_full", "回収先の収納が足りませんでした。");
        MEANINGS.put("tool_unavailable", "必要な道具を利用できませんでした。");
        MEANINGS.put("unsafe_state", "安全条件を満たせませんでした。");
        MEANINGS.put("owner_unavailable", "必要な相手を利用できませんでした。");
        MEANINGS.put("companion_unavailable", "Companionを利用できませんでした。");
        MEANINGS.put("stale_state", "新しい状態を確認できませんでした。");
        MEANINGS.put("expired", "実行期限内に完了できませんでした。");
        MEANINGS.put("disconnected", "実行側の接続が失われました。");
        MEANINGS.put("retry_exhausted", "試行上限に達しました。");
        MEANINGS.put("action_failed", "操作を完了できませんでした。");
        MEANINGS.put("stopped", null); MEANINGS.put("replaced", null); MEANINGS.put("completed", null);
    }

    private static String label(String target) { return LABELS.containsKey(target) ? LABELS.get(target) : target; }
    private static String head(String status, String reason) {
        return status.equals("completed") ? "完了。"
                : status.equals("failed") ? "失敗[" + reason + "]。" : "取消[" + reason + "]。";
    }

    public static String renderFallback(String type, String target, String status, String reason,
                                        int requested, int acquired, int mined, boolean complete) {
        return label(target) + TASKS.get(type) + ": " + head(status, reason)
                + bodyFor(type, target, status, reason, requested, acquired, mined, complete);
    }

    private static String bodyFor(String type, String target, String status, String reason,
                                  int requested, int acquired, int mined, boolean complete) {
        List<String[]> metrics = METRICS.get(type);
        String label = label(target);
        StringBuilder parts = new StringBuilder();
        for (int i = 0; i < metrics.size(); i++) {
            String key = metrics.get(i)[0], mlabel = metrics.get(i)[1], unit = metrics.get(i)[2];
            int value = key.equals("acquired") ? acquired : mined;
            if (i > 0) { parts.append("、"); }
            if (status.equals("completed") && i == 0) {
                parts.append(label).append("を").append(value).append("/").append(requested).append(unit).append(mlabel);
            } else if (i == 0) {
                parts.append(mlabel).append(value).append("/").append(requested).append(unit);
            } else {
                parts.append(mlabel).append(value).append(unit);
            }
        }
        if (status.equals("completed")) { return parts + "。"; }
        StringBuilder result = new StringBuilder((complete ? "" : "確定分は")).append(parts).append("。");
        if (!complete) { result.append("未確定の操作があります。"); }
        if (status.equals("failed") && MEANINGS.containsKey(reason) && MEANINGS.get(reason) != null) {
            result.append(MEANINGS.get(reason));
        } else if (!MEANINGS.containsKey(reason)) {
            result.append("終了理由: ").append(reason).append("。");
        }
        return result.toString();
    }

    /** Up to three fact-complete candidates for the LLM to choose among; mirrors Python render_candidates. */
    public static List<String[]> renderCandidates(String type, String target, String status, String reason,
                                                  int requested, int acquired, int mined, boolean complete) {
        String concise = renderFallback(type, target, status, reason, requested, acquired, mined, complete);
        String fact = head(status, reason) + bodyFor(type, target, status, reason, requested, acquired, mined, complete);
        String labelTask = label(target) + TASKS.get(type);
        List<String[]> candidates = new ArrayList<String[]>();
        candidates.add(new String[] {"friendly", labelTask + "だよ。" + fact});
        candidates.add(new String[] {"calm", labelTask + "の結果です。" + fact});
        candidates.add(new String[] {"concise", concise});
        for (String[] candidate : candidates) {
            if (candidate[1].getBytes(StandardCharsets.UTF_16LE).length / 2 > 512) { candidate[1] = concise; }
        }
        return candidates;
    }

    /** The say for a chosen variant ID, or the fixed fallback for an unknown/absent ID. */
    public static String selectCandidate(String type, String target, String status, String reason,
                                         int requested, int acquired, int mined, boolean complete, String variantId) {
        for (String[] candidate : renderCandidates(type, target, status, reason, requested, acquired, mined, complete)) {
            if (candidate[0].equals(variantId)) { return candidate[1]; }
        }
        return renderFallback(type, target, status, reason, requested, acquired, mined, complete);
    }
}
