package local.mcai;

import com.google.gson.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

/** Provider-independent transport; no Minecraft classes or credentials. */
public final class PingClient {
    private final String baseUrl;
    public PingClient(String baseUrl) { this.baseUrl = baseUrl; }

    public String ping(String player) throws IOException {
        return turn(player, "!agent ping", null);
    }

    public String turn(String player, String text, String session) throws IOException {
        return requestTurn(player, text, session, false).say;
    }

    public static final class Reply {
        public final String say, intent;
        Reply(String say, String intent) { this.say = say; this.intent = intent; }
    }

    public Reply socialTurn(String player, String text, String session) throws IOException {
        return requestTurn(player, text, session, text.startsWith("!agent chat "));
    }

    private Reply requestTurn(String player, String text, String session, boolean withIntent) throws IOException {
        URL url = new URL(baseUrl.replaceAll("/+$", "") + "/v1/turn");
        if (!(url.getProtocol().equals("http") || url.getProtocol().equals("https"))
                || url.getUserInfo() != null || url.getQuery() != null || url.getRef() != null) {
            throw new IOException("Invalid daemon URL");
        }
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(2000);
        connection.setReadTimeout(text.equals("!agent ping") ? 3000 : 50000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setDoOutput(true);
        JsonObject event = new JsonObject();
        event.addProperty("type", "player_chat");
        event.addProperty("player", player);
        event.addProperty("text", text);
        JsonObject request = new JsonObject();
        request.addProperty("version", 1);
        request.add("event", event);
        if (session != null) { request.addProperty("session", session); }
        if (withIntent) { request.addProperty("acceptIntent", true); }
        byte[] payload = request.toString().getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(payload.length);
        try {
            try (OutputStream out = connection.getOutputStream()) { out.write(payload); }
            if (connection.getResponseCode() != 200) {
                throw new IOException("Daemon HTTP status " + connection.getResponseCode());
            }
            try (InputStream in = connection.getInputStream();
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[1024];
                int count;
                while ((count = in.read(buffer)) != -1) {
                    if (out.size() + count > 8192) { throw new IOException("Response too large"); }
                    out.write(buffer, 0, count);
                }
                String raw = new String(out.toByteArray(), StandardCharsets.UTF_8);
                return withIntent ? parseSocialReply(raw) : new Reply(parseReply(raw), "none");
            }
        } finally { connection.disconnect(); }
    }

    static String parseReply(String text) throws IOException {
        try {
            JsonObject reply = new JsonParser().parse(text).getAsJsonObject();
            JsonPrimitive version = reply.getAsJsonPrimitive("version");
            JsonPrimitive say = reply.getAsJsonPrimitive("say");
            if (version == null || !version.isNumber() || !version.getAsString().equals("1")
                    || say == null || !say.isString() || say.getAsString().length() > 512
                    || !reply.has("actions") || !reply.get("actions").isJsonArray()
                    || reply.getAsJsonArray("actions").size() != 0) {
                throw new IOException("Invalid daemon response");
            }
            return say.getAsString();
        } catch (RuntimeException error) {
            throw new IOException("Malformed daemon response", error);
        }
    }

    static Reply parseSocialReply(String text) throws IOException {
        String say = parseReply(text);
        try {
            JsonObject o = new JsonParser().parse(text).getAsJsonObject();
            if (o.entrySet().size() != 4) { throw new IOException("Unexpected social fields"); }
            String intent = ActionProtocol.string(o, "intent");
            if (!(intent.equals("none") || intent.equals("follow_owner") || intent.equals("stop")
                    || intent.equals("look_at_owner") || intent.equals("pickup_item") || intent.equals("deposit_items"))) {
                throw new IOException("Unsupported intent");
            }
            return new Reply(say, intent);
        } catch (RuntimeException e) { throw new IOException("Malformed social intent", e); }
    }

    /** A chosen terminal presentation. `mode` is social or fallback; `say` is the fact-complete text. */
    public static final class Presentation {
        public final String say, variantId, mode;
        Presentation(String say, String variantId, String mode) { this.say = say; this.variantId = variantId; this.mode = mode; }
    }

    /** Bounded notification call: the Daemon picks a variant; the Forge re-checks the text. Max ~9s. */
    public Presentation presentTerminal(String player, String conversationSession, String deliveryId,
                                        String daemonEpoch, String executionSession, String skillInstanceId,
                                        String terminalId) throws IOException {
        JsonObject body = binding(conversationSession, player, deliveryId, daemonEpoch, executionSession,
                                  skillInstanceId, terminalId);
        JsonObject o = postJson("/v2/social/skill-terminal", body, 2000, 9000);
        try {
            String say = ActionProtocol.string(o, "say");
            String variantId = ActionProtocol.string(o, "variantId");
            String mode = ActionProtocol.string(o, "mode");
            if (say.length() > 512 || !(mode.equals("social") || mode.equals("fallback"))) {
                throw new IOException("Invalid presentation");
            }
            return new Presentation(say, variantId, mode);
        } catch (RuntimeException e) { throw new IOException("Malformed presentation", e); }
    }

    /** Short-deadline ACK; returns true only on an accepted acknowledgement. */
    public boolean deliverTerminal(String player, String conversationSession, String deliveryId,
                                   String daemonEpoch, String executionSession, String skillInstanceId,
                                   String terminalId, String outcome, String variantId) throws IOException {
        JsonObject body = binding(conversationSession, player, deliveryId, daemonEpoch, executionSession,
                                  skillInstanceId, terminalId);
        body.addProperty("outcome", outcome);
        if (variantId != null) { body.addProperty("variantId", variantId); }
        JsonObject o = postJson("/v2/social/terminal-delivery", body, 2000, 3000);
        return o.has("accepted") && o.get("accepted").isJsonPrimitive() && o.get("accepted").getAsBoolean();
    }

    private JsonObject binding(String conversationSession, String player, String deliveryId, String daemonEpoch,
                               String executionSession, String skillInstanceId, String terminalId) {
        JsonObject body = new JsonObject();
        body.addProperty("version", 2); body.addProperty("daemonEpoch", daemonEpoch);
        body.addProperty("session", executionSession); body.addProperty("skillInstanceId", skillInstanceId);
        body.addProperty("terminalId", terminalId); body.addProperty("conversationSession", conversationSession);
        body.addProperty("player", player); body.addProperty("deliveryId", deliveryId);
        return body;
    }

    private JsonObject postJson(String path, JsonObject body, int connectMs, int readMs) throws IOException {
        URL url = new URL(baseUrl.replaceAll("/+$", "") + path);
        if (!(url.getProtocol().equals("http") || url.getProtocol().equals("https"))
                || url.getUserInfo() != null || url.getQuery() != null || url.getRef() != null) {
            throw new IOException("Invalid daemon URL");
        }
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(connectMs); connection.setReadTimeout(readMs);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setDoOutput(true);
        byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
        if (payload.length > 8192) { throw new IOException("Request too large"); }
        connection.setFixedLengthStreamingMode(payload.length);
        try {
            try (OutputStream out = connection.getOutputStream()) { out.write(payload); }
            if (connection.getResponseCode() != 200) { throw new IOException("Daemon HTTP status " + connection.getResponseCode()); }
            try (InputStream in = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[1024]; int count;
                while ((count = in.read(buffer)) != -1) {
                    if (out.size() + count > 8192) { throw new IOException("Response too large"); }
                    out.write(buffer, 0, count);
                }
                return new JsonParser().parse(new String(out.toByteArray(), StandardCharsets.UTF_8)).getAsJsonObject();
            }
        } finally { connection.disconnect(); }
    }
}
