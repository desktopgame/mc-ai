package local.mcai;

import com.google.gson.*;
import org.junit.Test;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import static org.junit.Assert.*;

/** Fixed terminal renderer, delivery ledger and terminal-event parsing. Minecraft-independent. */
public class TerminalSocialTest {
    private JsonObject loadFixture() throws Exception {
        for (String candidate : new String[] {"../protocol/fixtures/skill-terminal-fallback.json",
                "protocol/fixtures/skill-terminal-fallback.json"}) {
            File file = new File(candidate);
            if (file.isFile()) {
                return new JsonParser().parse(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)).getAsJsonObject();
            }
        }
        throw new IllegalStateException("fixture not found");
    }

    @Test public void javaRendererMatchesSharedFixture() throws Exception {
        for (JsonElement element : loadFixture().getAsJsonArray("cases")) {
            JsonObject c = element.getAsJsonObject();
            JsonObject target = c.getAsJsonObject("target");
            JsonObject progress = c.getAsJsonObject("progress");
            String type = c.get("type").getAsString();
            String targetName = target.has("item") ? target.get("item").getAsString() : target.get("block").getAsString();
            int acquired = progress.has("acquired") ? progress.get("acquired").getAsInt() : 0;
            int mined = progress.has("mined") ? progress.get("mined").getAsInt() : 0;
            String say = TerminalPresentation.renderFallback(type, targetName, c.get("status").getAsString(),
                    c.get("reason").getAsString(), progress.get("requested").getAsInt(), acquired, mined,
                    progress.get("complete").getAsBoolean());
            assertEquals(c.get("id").getAsString(), c.get("fallback").getAsString(), say);
            assertTrue("over 512 UTF-16 units", say.getBytes(StandardCharsets.UTF_16LE).length / 2 <= 512);
        }
    }

    @Test public void terminalCursorResetsPerBindingAndNeverGoesBackward() {
        TerminalPollCursor cursor = new TerminalPollCursor();
        assertEquals(0, cursor.afterSequence());
        cursor.observe(3); cursor.observe(8); cursor.observe(5);
        assertEquals(8, cursor.afterSequence());          // monotonic within one binding
        cursor.schedule(100L);
        assertFalse(cursor.due(100L));
        assertTrue(cursor.due(100L + 1000000000L));
        cursor.reset();                                   // world/session change
        assertEquals(0, cursor.afterSequence());
        assertTrue(cursor.due(0L));                       // a new session fetches from eventSequence 1
    }

    @Test public void deliveryLedgerDisplaysAtMostOnceAndFirstWins() {
        TerminalDeliveryState state = new TerminalDeliveryState();
        long window = 12000;
        assertTrue(state.accept("a", "FA", 0, window));
        assertFalse(state.accept("a", "FA", 1, window));       // duplicate never re-enqueued
        assertEquals("SAY", state.finishSocial("a", "SAY"));   // social wins first
        assertNull(state.finishFallback("a"));                 // timeout loses the same tick
        assertTrue(state.known("a"));
        assertTrue(state.accept("b", "FB", 0, window));
        assertEquals("FB", state.finishFallback("b"));         // timeout path
        assertNull(state.finishSocial("b", "SB"));
        assertTrue(state.accept("c", "FC", 0, window));
        assertEquals(java.util.Arrays.asList("FC"), state.fallbackExpired(window));
        assertNull(state.finishFallback("c"));
        state.suppress("a");
        assertFalse(state.accept("a", "FA", 0, window));
    }

    private JsonObject eventJson() {
        return new JsonParser().parse("{\"version\":2,\"category\":\"skill_terminal\",\"terminalId\":\"t-1\","
                + "\"eventSequence\":17,\"daemonEpoch\":\"boot\",\"session\":\"world\",\"goalRevision\":42,"
                + "\"skillInstanceId\":\"s-1\",\"type\":\"collect_block\",\"status\":\"failed\","
                + "\"reason\":\"drop_unavailable\",\"target\":{\"block\":\"minecraft:log\"},"
                + "\"progress\":{\"requested\":5,\"acquired\":2,\"mined\":3,\"complete\":true}}").getAsJsonObject();
    }

    @Test public void parsesAndValidatesTerminalEvent() {
        SkillProtocol.TerminalEvent event = new SkillProtocol.TerminalEvent(eventJson());
        assertEquals("drop_unavailable", event.reason);
        assertEquals(2, event.acquired);
        assertEquals(3, event.mined);
        assertEquals("boot|world|s-1|t-1", event.identity());
        assertTrue(event.renderFallback().contains("drop_unavailable"));
    }

    @Test public void rejectsMalformedTerminalEvents() {
        reject(json -> json.addProperty("extra", 1));
        reject(json -> json.addProperty("category", "other"));
        reject(json -> json.getAsJsonObject("progress").addProperty("acquired", true));
        reject(json -> json.getAsJsonObject("progress").addProperty("acquired", -1));
        reject(json -> json.addProperty("status", "running"));
        reject(json -> json.addProperty("type", "attack"));
        reject(json -> json.getAsJsonObject("target").addProperty("item", "minecraft:log"));  // wrong field for block
        // completed must carry the success metric at requested
        JsonObject completed = eventJson();
        completed.addProperty("status", "completed");
        try { new SkillProtocol.TerminalEvent(completed); fail("accepted invalid completed"); }
        catch (IllegalArgumentException expected) { }
    }

    private void reject(SkillProtocolTest.Action<JsonObject> mutation) {
        JsonObject json = eventJson();
        mutation.run(json);
        try { new SkillProtocol.TerminalEvent(json); fail("accepted malformed event"); }
        catch (IllegalArgumentException expected) { }
    }
}
