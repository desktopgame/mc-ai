package local.mcai;

import java.text.Normalizer;
import java.util.Arrays;

/** Whole-message stop aliases only; no substring, negation, quotation or conditional matching. */
public final class ImmediateStop {
    public static boolean matches(String text) {
        String value = Normalizer.normalize(text, Normalizer.Form.NFKC).trim().replaceAll("[!！。]+$", "").trim();
        return Arrays.asList("止まって", "止まってください", "止まれ", "停止して", "停止してください", "やめて", "やめてください",
                "やっぱやめて", "やっぱりやめて", "キャンセル", "キャンセルして", "キャンセルしてください",
                "待って", "ちょっと待って", "その場で待って").contains(value);
    }
}
