package local.mcai;

import com.google.gson.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class SkillProtocolTest {
    private JsonObject view() {
        return new JsonParser().parse("{"
                + "\"version\":2,\"session\":\"world\",\"daemonEpoch\":\"boot\",\"goalRevision\":3,"
                + "\"status\":\"running\",\"error\":null,"
                + "\"skill\":{\"skillInstanceId\":\"skill-1\",\"type\":\"collect_drop\","
                + "\"target\":{\"item\":\"minecraft:log\"},\"phase\":\"waiting_action\","
                + "\"progress\":{\"requested\":5,\"acquired\":2,\"complete\":false},\"result\":null},"
                + "\"action\":{\"type\":\"pickup_target\",\"actionId\":\"action-3\",\"actionSequence\":3,"
                + "\"skillInstanceId\":\"skill-1\",\"companionId\":\"companion\",\"dimension\":0,"
                + "\"targetRef\":\"item-uuid\",\"item\":\"minecraft:log\",\"maxCount\":3,"
                + "\"timeoutMs\":30000,\"observationSequence\":21}}").getAsJsonObject();
    }

    @Test public void parsesAValidViewAndAction() {
        JsonObject o = view();
        SkillProtocol.validateView(o, "world", 3, "boot");
        SkillProtocol.Skill skill = SkillProtocol.skill(o);
        assertEquals("skill-1", skill.skillInstanceId);
        assertEquals(5, skill.requested);
        assertEquals(2, skill.achieved);
        assertNull(skill.resultStatus);
        SkillProtocol.Action action = SkillProtocol.action(o);
        assertEquals("action-3", action.actionId);
        assertEquals(3, action.sequence);
        assertEquals(3, action.maxCount);
        assertEquals("item-uuid", action.targetRef);
        assertEquals("minecraft:log", action.item);
    }

    @Test public void terminalViewCarriesTheResult() {
        JsonObject o = view();
        o.addProperty("status", "completed");
        o.add("action", JsonNull.INSTANCE);
        JsonObject skill = o.getAsJsonObject("skill");
        skill.addProperty("phase", "terminal");
        skill.add("result", new JsonParser().parse("{\"skillInstanceId\":\"skill-1\",\"status\":\"completed\","
                + "\"reason\":\"completed\",\"progress\":{\"requested\":5,\"acquired\":5,\"complete\":true}}"));
        SkillProtocol.validateView(o, "world", 3, "boot");
        SkillProtocol.Skill parsed = SkillProtocol.skill(o);
        assertEquals("completed", parsed.resultStatus);
        assertEquals("completed", parsed.resultReason);
        assertNull(SkillProtocol.action(o));
    }

    @Test public void parsesAMineViewAndAction() {
        JsonObject o = new JsonParser().parse("{\"version\":2,\"session\":\"world\",\"daemonEpoch\":\"boot\",\"goalRevision\":1,"
                + "\"status\":\"running\",\"error\":null,"
                + "\"skill\":{\"skillInstanceId\":\"s\",\"type\":\"mine\",\"target\":{\"block\":\"minecraft:iron_ore\"},"
                + "\"phase\":\"waiting_action\",\"progress\":{\"requested\":1,\"mined\":0,\"complete\":false},\"result\":null},"
                + "\"action\":{\"type\":\"mine_target\",\"actionId\":\"a\",\"actionSequence\":1,\"skillInstanceId\":\"s\","
                + "\"companionId\":\"c\",\"dimension\":0,\"targetRef\":\"block-1_2_3\",\"block\":\"minecraft:iron_ore\","
                + "\"timeoutMs\":30000,\"observationSequence\":5}}").getAsJsonObject();
        SkillProtocol.validateView(o, "world", 1, "boot");
        SkillProtocol.Skill skill = SkillProtocol.skill(o);
        assertEquals("block", skill.field);
        assertEquals("minecraft:iron_ore", skill.name);
        assertEquals(0, skill.achieved);
        SkillProtocol.Action action = SkillProtocol.action(o);
        assertEquals("mine_target", action.type);
        assertEquals("minecraft:iron_ore", action.block);
        assertNull(action.item);
        assertEquals(1, action.maxCount);
        assertEquals("block", action.field());
    }

    private void reject(Action<JsonObject> mutation) {
        JsonObject o = view();
        mutation.run(o);
        try {
            SkillProtocol.validateView(o, "world", 3, "boot");
            SkillProtocol.skill(o);
            SkillProtocol.action(o);
            fail("Accepted invalid view");
        } catch (IllegalArgumentException | NullPointerException expected) { }
    }

    interface Action<T> { void run(T value); }

    @Test public void rejectsStaleOrMalformedViews() {
        for (String session : new String[] {"old", "world"}) {
            try { SkillProtocol.validateView(view(), session, 2, "boot"); fail("accepted stale revision"); }
            catch (IllegalArgumentException expected) { }
        }
        reject(o -> o.addProperty("extra", "x"));
        reject(o -> o.addProperty("daemonEpoch", "other"));
        reject(o -> o.getAsJsonObject("skill").getAsJsonObject("target").addProperty("item", "minecraft:diamond"));
        reject(o -> o.getAsJsonObject("action").addProperty("type", "mine_forever"));
        reject(o -> o.getAsJsonObject("action").addProperty("maxCount", 65));
        reject(o -> o.getAsJsonObject("action").addProperty("item", "minecraft:diamond"));
        reject(o -> o.getAsJsonObject("action").addProperty("actionSequence", 0));
        reject(o -> o.getAsJsonObject("action").addProperty("extra", 1));
        reject(o -> o.getAsJsonObject("skill").getAsJsonObject("progress").addProperty("complete", "yes"));
    }

    @Test public void executionStatePreventsReexecutionAndBoundsTheLedger() {
        SkillExecutionState state = new SkillExecutionState();
        state.reset("world"); state.revision = 3;
        assertTrue(state.claim("A", 1));
        assertFalse(state.claim("B", 2));       // one outstanding action at a time
        state.complete("succeeded", "completed", 3);
        assertTrue(state.known("A"));
        assertFalse(state.claim("A", 1));       // an action never runs twice
        assertFalse(state.claim("C", 1));       // sequences never move backwards
        assertTrue(state.claim("C", 2));
        state.complete("failed", "target_lost", 0);
        assertTrue(state.claim("D", 3));
        state.complete("succeeded", "completed", 1);
        assertTrue(state.claim("E", 4));
        for (int i = 0; i < 300; i++) { state.claim("action-" + i, 100 + i); state.complete("succeeded", "completed", 1); }
        assertTrue(state.known("action-299"));
        assertFalse(state.known("A"));           // oldest entries are evicted
    }
}
