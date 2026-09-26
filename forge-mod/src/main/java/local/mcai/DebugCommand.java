package local.mcai;

/** Strict manual-command allowlist, independent of Minecraft. */
public final class DebugCommand {
    public final String type;
    public final String text;
    private DebugCommand(String type, String text) { this.type = type; this.text = text; }

    public static DebugCommand parse(String input) {
        String[] parts = input.trim().split("\\s+", 3);
        if (parts.length < 2 || !parts[0].equals("!agent")) {
            throw new IllegalArgumentException("コマンドを指定してください。");
        }
        String type = parts[1];
        if (type.equals("say")) {
            if (parts.length != 3 || parts[2].trim().isEmpty() || parts[2].length() > 256) {
                throw new IllegalArgumentException("使い方: !agent say メッセージ");
            }
            for (char c : parts[2].toCharArray()) {
                if (Character.isISOControl(c) || c == '\u00a7') {
                    throw new IllegalArgumentException("使用できない文字が含まれています。");
                }
            }
            return new DebugCommand(type, parts[2]);
        }
        if (!(type.equals("spawn") || type.equals("follow") || type.equals("stop") || type.equals("look")
                || type.equals("pickup") || type.equals("deposit") || type.equals("status") || type.equals("help")) || parts.length != 2) {
            throw new IllegalArgumentException("使い方: !agent spawn / follow / stop / look / pickup / deposit / say メッセージ / status");
        }
        return new DebugCommand(type, "");
    }
}
