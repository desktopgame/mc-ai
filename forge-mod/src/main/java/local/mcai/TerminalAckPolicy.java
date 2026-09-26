package local.mcai;

/**
 * Pure decision for when a completed terminal presentation may actually be displayed.
 *
 * A presentation is displayed only if its conversation epoch still matches and the owner is present.
 * If a reset/forget/owner or world change happened while the Social worker was running, the result
 * must be suppressed (never displayed, never registered in history). Minecraft-independent.
 */
public final class TerminalAckPolicy {
    private TerminalAckPolicy() { }

    public static boolean shouldDisplay(long doneEpoch, long currentEpoch, boolean ownerPresent) {
        return doneEpoch == currentEpoch && ownerPresent;
    }
}
