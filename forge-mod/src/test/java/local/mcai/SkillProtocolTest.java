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
        // The outer progress and the terminal result progress must agree exactly.
        skill.getAsJsonObject("progress").addProperty("acquired", 5);
        skill.getAsJsonObject("progress").addProperty("complete", true);
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

    @Test public void parsesACollectBlockView() {
        JsonObject o = new JsonParser().parse("{\"version\":2,\"session\":\"world\",\"daemonEpoch\":\"boot\",\"goalRevision\":2,"
                + "\"status\":\"running\",\"error\":null,"
                + "\"skill\":{\"skillInstanceId\":\"s\",\"type\":\"collect_block\",\"target\":{\"block\":\"minecraft:log\"},"
                + "\"phase\":\"waiting_action\",\"progress\":{\"requested\":5,\"acquired\":2,\"mined\":3,\"complete\":false},\"result\":null},"
                + "\"action\":null}").getAsJsonObject();
        SkillProtocol.validateView(o, "world", 2, "boot");
        SkillProtocol.Skill skill = SkillProtocol.skill(o);
        assertEquals("block", skill.field);
        assertEquals("minecraft:log", skill.name);
        assertEquals(5, skill.requested);
        assertEquals(2, skill.acquired);
        assertEquals(3, skill.mined);
        assertEquals(2, skill.achieved);   // success is measured by acquired, never by mined
        assertFalse(skill.complete);
    }

    @Test public void collectBlockProgressUnionIsStrict() {
        String base = "{\"version\":2,\"session\":\"world\",\"daemonEpoch\":\"boot\",\"goalRevision\":2,"
                + "\"status\":\"running\",\"error\":null,"
                + "\"skill\":{\"skillInstanceId\":\"s\",\"type\":\"collect_block\",\"target\":{\"block\":\"minecraft:log\"},"
                + "\"phase\":\"waiting_action\",\"progress\":%s,\"result\":null},\"action\":null}";
        // Missing mined / extra key / acquired over requested are all rejected.
        for (String progress : new String[] {
                "{\"requested\":5,\"acquired\":2,\"complete\":false}",
                "{\"requested\":5,\"acquired\":2,\"mined\":3,\"complete\":false,\"extra\":1}",
                "{\"requested\":2,\"acquired\":3,\"mined\":0,\"complete\":false}"}) {
            JsonObject o = new JsonParser().parse(String.format(base, progress)).getAsJsonObject();
            try { SkillProtocol.validateView(o, "world", 2, "boot"); SkillProtocol.skill(o); fail("accepted " + progress); }
            catch (IllegalArgumentException expected) { }
        }
    }

    @Test public void unsupportedCollectBlockTargetAndMapping() {
        assertEquals("minecraft:log", SkillProtocol.collectItemFor("minecraft:log"));
        assertNull(SkillProtocol.collectItemFor("minecraft:dirt"));
        assertEquals("collect_block_v1", SkillProtocol.capabilityFor("collect_block"));
        assertEquals("mine_v1", SkillProtocol.capabilityFor("mine"));
        JsonObject o = new JsonParser().parse("{\"version\":2,\"session\":\"world\",\"daemonEpoch\":\"boot\",\"goalRevision\":1,"
                + "\"status\":\"running\",\"error\":null,"
                + "\"skill\":{\"skillInstanceId\":\"s\",\"type\":\"collect_block\",\"target\":{\"block\":\"minecraft:dirt\"},"
                + "\"phase\":\"selecting\",\"progress\":{\"requested\":1,\"acquired\":0,\"mined\":0,\"complete\":false},\"result\":null},"
                + "\"action\":null}").getAsJsonObject();
        try { SkillProtocol.validateView(o, "world", 1, "boot"); SkillProtocol.skill(o); fail("accepted unsupported block"); }
        catch (IllegalArgumentException expected) { }
    }

    @Test public void parsesCapabilities() {
        JsonObject o = new JsonParser().parse("{\"version\":2,\"session\":\"world\",\"daemonEpoch\":\"boot\","
                + "\"capabilities\":[\"collect_drop_v1\",\"mine_v1\",\"collect_block_v1\"]}").getAsJsonObject();
        java.util.Set<String> caps = SkillProtocol.capabilities(o);
        assertTrue(caps.contains("collect_block_v1"));
        assertFalse(SkillProtocol.capabilities(new JsonObject()).contains("collect_block_v1"));
    }

    @Test public void executionStateHandlesAMixedSkillSequence() {
        SkillExecutionState state = new SkillExecutionState();
        state.reset("world"); state.revision = 1;
        assertTrue(state.claim("A1", 1)); state.complete("succeeded", "completed", 1);   // mine
        assertTrue(state.claim("A2", 2)); state.complete("succeeded", "completed", 2);   // pickup
        assertTrue(state.claim("A3", 3)); state.complete("succeeded", "completed", 1);   // mine again
        assertFalse(state.claim("A1", 1));   // an old action never re-runs
        assertFalse(state.claim("A3", 3));   // nor a settled one
        assertTrue(state.known("A1"));
        assertTrue(state.known("A3"));
    }

    // ---- skill/action binding --------------------------------------------
    private SkillProtocol.Skill skillOf(String json) { return new SkillProtocol.Skill(new JsonParser().parse(json).getAsJsonObject()); }
    private SkillProtocol.Action actionOf(String json) { return new SkillProtocol.Action(new JsonParser().parse(json).getAsJsonObject()); }

    private String skillJson(String id, String type, String target, int requested, String counter, int value) {
        String progress = type.equals("collect_block")
                ? "{\"requested\":" + requested + ",\"acquired\":" + (counter.equals("acquired") ? value : 0)
                    + ",\"mined\":" + (counter.equals("mined") ? value : 0) + ",\"complete\":false}"
                : "{\"requested\":" + requested + ",\"" + counter + "\":" + value + ",\"complete\":false}";
        return "{\"skillInstanceId\":\"" + id + "\",\"type\":\"" + type + "\",\"target\":{\""
                + (type.equals("collect_drop") ? "item" : "block") + "\":\"" + target + "\"},"
                + "\"phase\":\"waiting_action\",\"progress\":" + progress + ",\"result\":null}";
    }
    private String pickupJson(String id, String item) {
        return "{\"type\":\"pickup_target\",\"actionId\":\"x\",\"actionSequence\":1,\"skillInstanceId\":\"" + id
                + "\",\"companionId\":\"c\",\"dimension\":0,\"targetRef\":\"item-uuid\",\"item\":\"" + item
                + "\",\"maxCount\":2,\"timeoutMs\":30000,\"observationSequence\":3}";
    }
    private String mineJson(String id, String block) {
        return "{\"type\":\"mine_target\",\"actionId\":\"y\",\"actionSequence\":2,\"skillInstanceId\":\"" + id
                + "\",\"companionId\":\"c\",\"dimension\":0,\"targetRef\":\"block-0_64_0\",\"block\":\"" + block
                + "\",\"timeoutMs\":30000,\"observationSequence\":3}";
    }
    private void rejectBinding(SkillProtocol.Skill skill, SkillProtocol.Action action) {
        try { SkillProtocol.validateBinding(skill, action); fail("accepted binding"); }
        catch (IllegalArgumentException expected) { }
    }

    @Test public void bindingRejectsMismatchedSkillAndAction() {
        SkillProtocol.Skill cb = skillOf(skillJson("A", "collect_block", "minecraft:log", 5, "mined", 0));
        SkillProtocol.validateBinding(cb, actionOf(pickupJson("A", "minecraft:log")));
        SkillProtocol.validateBinding(cb, actionOf(mineJson("A", "minecraft:log")));
        rejectBinding(cb, actionOf(pickupJson("A", "minecraft:cobblestone")));
        rejectBinding(cb, actionOf(mineJson("A", "minecraft:cobblestone")));
        rejectBinding(cb, actionOf(pickupJson("B", "minecraft:log")));

        SkillProtocol.Skill drop = skillOf(skillJson("D", "collect_drop", "minecraft:log", 2, "acquired", 0));
        rejectBinding(drop, actionOf(mineJson("D", "minecraft:log")));
        SkillProtocol.validateBinding(drop, actionOf(pickupJson("D", "minecraft:log")));

        SkillProtocol.Skill mine = skillOf(skillJson("M", "mine", "minecraft:log", 1, "mined", 0));
        rejectBinding(mine, actionOf(pickupJson("M", "minecraft:log")));
        SkillProtocol.validateBinding(mine, actionOf(mineJson("M", "minecraft:log")));
        rejectBinding(mine, actionOf(mineJson("M", "minecraft:cobblestone")));

        // An unsupported action type is rejected by the Action parser itself.
        try {
            actionOf("{\"type\":\"mine_forever\",\"actionId\":\"x\",\"actionSequence\":1,\"skillInstanceId\":\"A\","
                    + "\"companionId\":\"c\",\"dimension\":0,\"targetRef\":\"r\",\"block\":\"minecraft:log\","
                    + "\"timeoutMs\":30000,\"observationSequence\":3}");
            fail("accepted unknown action type");
        } catch (IllegalArgumentException expected) { }
    }

    @Test public void goalBindingMatchesTheStartedGoal() {
        SkillProtocol.Skill cb = skillOf(skillJson("A", "collect_block", "minecraft:log", 5, "mined", 0));
        SkillProtocol.validateGoalBinding(cb, "collect_block", "minecraft:log", 5);
        for (Object[] bad : new Object[][] {{"mine", "minecraft:log", 5}, {"collect_block", "minecraft:cobblestone", 5},
                {"collect_block", "minecraft:log", 4}}) {
            try { SkillProtocol.validateGoalBinding(cb, (String) bad[0], (String) bad[1], (Integer) bad[2]); fail("accepted"); }
            catch (IllegalArgumentException expected) { }
        }
        try { SkillProtocol.validateGoalBinding(null, "collect_block", "minecraft:log", 5); fail("accepted null"); }
        catch (IllegalArgumentException expected) { }
    }

    // ---- terminal result strictness --------------------------------------
    private JsonObject terminal(String type, String targetJson, String outer, String status, String resultProgress, String resultId) {
        String s = "{\"version\":2,\"session\":\"world\",\"daemonEpoch\":\"boot\",\"goalRevision\":1,"
                + "\"status\":\"" + status + "\",\"error\":null,"
                + "\"skill\":{\"skillInstanceId\":\"A\",\"type\":\"" + type + "\",\"target\":" + targetJson + ","
                + "\"phase\":\"terminal\",\"progress\":" + outer + ","
                + "\"result\":{\"skillInstanceId\":\"" + resultId + "\",\"status\":\"" + status + "\",\"reason\":\"r\",\"progress\":" + resultProgress + "}},"
                + "\"action\":null}";
        return new JsonParser().parse(s).getAsJsonObject();
    }
    private SkillProtocol.Skill parseSkill(JsonObject view) { SkillProtocol.validateView(view, "world", 1, "boot"); return SkillProtocol.skill(view); }
    private void rejectSkill(JsonObject view) {
        try { parseSkill(view); fail("accepted invalid terminal view"); }
        catch (IllegalArgumentException expected) { }
    }

    @Test public void terminalResultMustMatchOuterProgressAndInvariants() {
        String cbTarget = "{\"block\":\"minecraft:log\"}";
        String cbGood = "{\"requested\":5,\"acquired\":5,\"mined\":2,\"complete\":true}";
        parseSkill(terminal("collect_block", cbTarget, cbGood, "completed", cbGood, "A"));
        rejectSkill(terminal("collect_block", cbTarget, cbGood, "completed", cbGood, "B"));       // result id mismatch
        rejectSkill(terminal("collect_block", cbTarget, cbGood, "unknown", cbGood, "A"));         // unknown status
        rejectSkill(terminal("collect_block", cbTarget, cbGood, "completed",
                "{\"requested\":5,\"acquired\":5,\"complete\":true}", "A"));                       // missing mined
        rejectSkill(terminal("collect_block", cbTarget, cbGood, "completed",
                "{\"requested\":5,\"acquired\":4,\"mined\":2,\"complete\":true}", "A"));           // outer/result mismatch
        String cbShort = "{\"requested\":5,\"acquired\":3,\"mined\":2,\"complete\":true}";
        rejectSkill(terminal("collect_block", cbTarget, cbShort, "completed", cbShort, "A"));     // completed < requested
        String cbIncomplete = "{\"requested\":5,\"acquired\":5,\"mined\":2,\"complete\":false}";
        rejectSkill(terminal("collect_block", cbTarget, cbIncomplete, "completed", cbIncomplete, "A"));
        String cbPartial = "{\"requested\":5,\"acquired\":2,\"mined\":3,\"complete\":true}";
        parseSkill(terminal("collect_block", cbTarget, cbPartial, "failed", cbPartial, "A"));     // partial failure allowed
        parseSkill(terminal("collect_block", cbTarget, cbPartial, "cancelled", cbPartial, "A"));

        String mineTarget = "{\"block\":\"minecraft:log\"}";
        rejectSkill(terminal("mine", mineTarget, "{\"requested\":1,\"mined\":0,\"complete\":true}",
                "completed", "{\"requested\":1,\"mined\":0,\"complete\":true}", "A"));
        parseSkill(terminal("mine", mineTarget, "{\"requested\":1,\"mined\":1,\"complete\":true}",
                "completed", "{\"requested\":1,\"mined\":1,\"complete\":true}", "A"));

        rejectSkill(terminal("collect_drop", "{\"item\":\"minecraft:log\"}",
                "{\"requested\":2,\"acquired\":1,\"complete\":true}", "completed",
                "{\"requested\":2,\"acquired\":1,\"complete\":true}", "A"));
    }

    @Test public void phaseAndResultMustAgree() {
        JsonObject terminalWithoutResult = view();
        terminalWithoutResult.getAsJsonObject("skill").addProperty("phase", "terminal");
        rejectSkill(terminalWithoutResult);   // phase terminal, result null

        JsonObject runningWithResult = view();
        runningWithResult.getAsJsonObject("skill").add("result", new JsonParser().parse(
                "{\"skillInstanceId\":\"skill-1\",\"status\":\"failed\",\"reason\":\"r\","
                + "\"progress\":{\"requested\":5,\"acquired\":2,\"complete\":false}}"));
        rejectSkill(runningWithResult);       // result on a non-terminal phase
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
