package local.mcai;

import com.google.gson.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Pure tick-order / authority rules for the Skill hardening: mutation guard, tick policy, fence. */
public class SkillHardeningTest {

    // ---- SkillMutationGuard -------------------------------------------------
    @Test public void mutationGuardBlocksUnsafeHealthAndOwnerLeash() {
        assertEquals("unsafe_state", SkillMutationGuard.evaluate(true, true, true, true, 6.0F, 4.0D, 0L, 100L, 100L));
        assertEquals("unsafe_state", SkillMutationGuard.evaluate(true, true, true, true, 20.0F, 1025.0D, 0L, 100L, 100L));
        assertNull(SkillMutationGuard.evaluate(true, true, true, true, 20.0F, 1024.0D, 0L, 100L, 100L));
        assertNull(SkillMutationGuard.evaluate(true, true, true, true, 20.0F, 0.0D, 0L, 100L, 100L));
    }

    @Test public void mutationGuardChecksLeaseDeadlineTargetAndAuthority() {
        assertEquals("disconnected", SkillMutationGuard.evaluate(true, true, true, true, 20.0F, 0.0D, 101L, 100L, 100L));
        assertEquals("expired", SkillMutationGuard.evaluate(true, true, true, true, 20.0F, 0.0D, 50L, 100L, 40L));
        assertEquals("target_lost", SkillMutationGuard.evaluate(true, true, true, false, 20.0F, 0.0D, 0L, 100L, 100L));
        assertEquals("companion_unavailable", SkillMutationGuard.evaluate(false, true, true, true, 20.0F, 0.0D, 0L, 100L, 100L));
        assertEquals("companion_unavailable", SkillMutationGuard.evaluate(true, false, true, true, 20.0F, 0.0D, 0L, 100L, 100L));
        assertEquals("companion_unavailable", SkillMutationGuard.evaluate(true, true, false, true, 20.0F, 0.0D, 0L, 100L, 100L));
    }

    // ---- SkillTickPolicy ----------------------------------------------------
    @Test public void aResolvedPositiveOutcomeIsFinalEvenWhenUnsafeOrExpired() {
        SkillTickPolicy.Decision d = SkillTickPolicy.decide(true, true, 1, "completed", true, true, true, true, true, true);
        assertEquals("succeeded", d.status);
        assertEquals(1, d.count);
        d = SkillTickPolicy.decide(false, true, 3, "completed", true, true, true, true, true, true);
        assertEquals("succeeded", d.status);
        assertEquals(3, d.count);
    }

    @Test public void aResolvedNegativeOutcomeUsesTheEntityReason() {
        SkillTickPolicy.Decision d = SkillTickPolicy.decide(true, true, 0, "blocked", true, true, true, false, false, false);
        assertEquals("failed", d.status);
        assertEquals("blocked", d.reason);
        assertEquals(0, d.count);
        assertEquals("tool_unavailable", SkillTickPolicy.decide(true, true, 0, "", true, true, true, false, false, false).reason);
        assertEquals("inventory_full", SkillTickPolicy.decide(false, true, 0, null, true, true, true, false, false, false).reason);
    }

    @Test public void anUnresolvedActionReportsTheFirstSafetyFailure() {
        assertEquals("companion_unavailable",
                SkillTickPolicy.decide(true, false, 0, null, false, true, true, false, false, false).reason);
        assertEquals("unsafe_state",
                SkillTickPolicy.decide(true, false, 0, null, true, true, true, true, false, false).reason);
        assertEquals("expired",
                SkillTickPolicy.decide(true, false, 0, null, true, true, true, false, true, false).reason);
        assertEquals("path_not_found",
                SkillTickPolicy.decide(true, false, 0, null, true, true, true, false, false, true).reason);
        assertTrue(SkillTickPolicy.decide(true, false, 0, null, true, true, true, false, false, false).waiting());
    }

    // ---- SkillRequestFence --------------------------------------------------
    private JsonObject view(String session, int revision, String epoch, String status) {
        JsonObject o = new JsonObject();
        o.addProperty("version", 2); o.addProperty("session", session); o.addProperty("daemonEpoch", epoch);
        o.addProperty("goalRevision", revision); o.addProperty("status", status); o.add("error", JsonNull.INSTANCE);
        o.add("skill", JsonNull.INSTANCE); o.add("action", JsonNull.INSTANCE);
        return o;
    }

    @Test public void advancingTheGenerationDiscardsOldRequests() {
        SkillRequestFence fence = new SkillRequestFence();
        SkillRequestFence.Request first = fence.goal("world", 1, "boot", 0L);
        assertTrue(fence.current(first));
        fence.advance();
        assertFalse(fence.current(first));
        assertTrue(fence.stale(first));
        SkillRequestFence.Request second = fence.goal("world", 2, "boot", 1L);
        assertTrue(fence.current(second));
        assertFalse(fence.current(null));
    }

    @Test public void openAckNeedsTheSameSessionAndAEpoch() {
        SkillRequestFence fence = new SkillRequestFence();
        SkillRequestFence.Request open = fence.open("world", 0L);
        assertTrue(SkillRequestFence.validOpenAck(open, view("world", 0, "boot", "idle")));
        assertFalse(SkillRequestFence.validOpenAck(open, view("other", 0, "boot", "idle")));
        assertFalse(SkillRequestFence.validOpenAck(open, null));
    }

    @Test public void cancelAckNeedsMatchingIdentityAndIdle() {
        SkillRequestFence fence = new SkillRequestFence();
        SkillRequestFence.Request cancel = fence.cancel("world", 3, "boot", 0L);
        assertTrue(SkillRequestFence.validCancelAck(cancel, view("world", 3, "boot", "idle")));
        assertFalse(SkillRequestFence.validCancelAck(cancel, view("world", 3, "boot", "running")));
        assertFalse(SkillRequestFence.validCancelAck(cancel, view("world", 2, "boot", "idle")));
        assertFalse(SkillRequestFence.validCancelAck(cancel, view("world", 3, "other", "idle")));
        assertFalse(SkillRequestFence.validCancelAck(cancel, view("other", 3, "boot", "idle")));
        assertFalse(SkillRequestFence.validCancelAck(cancel, null));
        assertFalse(SkillRequestFence.validCancelAck(null, view("world", 3, "boot", "idle")));
    }
}
