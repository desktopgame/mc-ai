package local.mcai;

/**
 * Final authority immediately before a Skill mutates the world or the companion inventory.
 *
 * The Forge tick is only a safety net; this guard is what actually gates {@code setBlockToAir} and
 * the pickup store. It is intentionally pure so the ordering rules stay unit-testable without a
 * live Minecraft world.
 */
public final class SkillMutationGuard {
    private SkillMutationGuard() { }

    /**
     * Returns a failure reason when the mutation must not happen, or {@code null} when it is safe.
     * Entity/owner authority is checked first, then the action-specific target identity, then the
     * shared safety conditions (health, owner leash, control lease, action deadline).
     */
    public static String evaluate(boolean companionAlive, boolean ownerAlive, boolean sameWorld,
                                  boolean targetValid, float health, double ownerDistanceSq,
                                  long now, long controlDeadline, long actionDeadline) {
        if (!companionAlive || !ownerAlive || !sameWorld) { return "companion_unavailable"; }
        if (!targetValid) { return "target_lost"; }
        if (health <= 6.0F) { return "unsafe_state"; }
        if (Double.isNaN(ownerDistanceSq) || ownerDistanceSq > 1024.0D) { return "unsafe_state"; }
        if (now > controlDeadline) { return "disconnected"; }
        if (now > actionDeadline) { return "expired"; }
        return null;
    }
}
