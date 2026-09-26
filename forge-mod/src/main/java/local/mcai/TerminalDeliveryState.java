package local.mcai;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure terminal-delivery ledger, independent of the current Skill lifecycle. Guarantees a terminal
 * result is displayed at most once: whichever of a timeout fallback or an HTTP completion resolves
 * first wins, and later completions return null. Keyed by terminal identity.
 */
public final class TerminalDeliveryState {
    public static final int MAX = 100;
    private static final class Entry {
        String fallback;
        long deadlineNanos;
    }
    private final LinkedHashMap<String, Entry> pending = new LinkedHashMap<String, Entry>();
    private final LinkedHashMap<String, String> closed = new LinkedHashMap<String, String>();

    public static String identity(String epoch, String session, String skillInstanceId, String terminalId) {
        return epoch + "|" + session + "|" + skillInstanceId + "|" + terminalId;
    }

    /** Registers a new event. Returns false for an already-known identity (no re-enqueue). */
    public boolean accept(String identity, String fallback, long now, long windowNanos) {
        if (pending.containsKey(identity) || closed.containsKey(identity)) { return false; }
        Entry entry = new Entry();
        entry.fallback = fallback;
        entry.deadlineNanos = now + windowNanos;
        pending.put(identity, entry);
        while (pending.size() + closed.size() > MAX) {
            if (!pending.isEmpty()) { pending.remove(pending.keySet().iterator().next()); }
            else { closed.remove(closed.keySet().iterator().next()); }
        }
        return true;
    }

    public boolean known(String identity) { return pending.containsKey(identity) || closed.containsKey(identity); }

    /** True once the identity was displayed or suppressed and can no longer be shown. */
    public boolean resolved(String identity) { return !pending.containsKey(identity) && closed.containsKey(identity); }

    /** Timeout path: first-wins display of the fixed fallback. Null when already displayed. */
    public String finishFallback(String identity) {
        Entry entry = pending.get(identity);
        return entry == null ? null : close(identity, entry.fallback);
    }

    /** Social path: first-wins display of the provider say. Null when already displayed. */
    public String finishSocial(String identity, String say) {
        if (!pending.containsKey(identity) || say == null) { return null; }
        return close(identity, say);
    }

    public void suppress(String identity) {
        if (pending.remove(identity) != null) { closed.put(identity, null); }
    }

    public List<String> expired(long now) {
        List<String> result = new ArrayList<String>();
        for (Map.Entry<String, Entry> entry : pending.entrySet()) {
            if (now >= entry.getValue().deadlineNanos) { result.add(entry.getKey()); }
        }
        return result;
    }

    /** Resolves every expired entry through the fallback path and returns the texts to display once. */
    public List<String> fallbackExpired(long now) {
        List<String> result = new ArrayList<String>();
        for (String identity : expired(now)) {
            String text = finishFallback(identity);
            if (text != null) { result.add(text); }
        }
        return result;
    }

    private String close(String identity, String text) {
        pending.remove(identity);
        closed.put(identity, text);
        return text;
    }
}
