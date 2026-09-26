package local.mcai;

import com.google.gson.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

/** Bounded protocol transport, independent of Minecraft and providers. */
public final class DaemonClient {
    private final String base;
    public DaemonClient(String base) { this.base = base; }
    public JsonObject post(String path, JsonObject body) throws IOException {
        URL url = new URL(base.replaceAll("/+$", "") + path);
        if (!(url.getProtocol().equals("http") || url.getProtocol().equals("https"))
                || url.getUserInfo() != null || url.getQuery() != null || url.getRef() != null) {
            throw new IOException("Invalid daemon URL");
        }
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(2000); conn.setReadTimeout(3000); conn.setInstanceFollowRedirects(false);
        conn.setRequestMethod("POST"); conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        byte[] data = body.toString().getBytes(StandardCharsets.UTF_8);
        if (data.length > 8192) { throw new IOException("Request too large"); }
        conn.setFixedLengthStreamingMode(data.length);
        try {
            try (OutputStream out = conn.getOutputStream()) { out.write(data); }
            if (conn.getResponseCode() != 200) { throw new IOException("Daemon rejected request"); }
            try (InputStream in = conn.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[1024]; int n;
                while ((n = in.read(buffer)) != -1) {
                    if (out.size() + n > 8192) { throw new IOException("Response too large"); }
                    out.write(buffer, 0, n);
                }
                return new JsonParser().parse(new String(out.toByteArray(), StandardCharsets.UTF_8)).getAsJsonObject();
            }
        } catch (RuntimeException e) { throw new IOException("Malformed response", e); }
        finally { conn.disconnect(); }
    }
}
