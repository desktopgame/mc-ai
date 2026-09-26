package local.mcai;

import com.google.gson.JsonObject;

/**
 * Generation fence for the v2 Skill HTTP requests.
 *
 * Shared {@code skillResponse}/{@code skillDone} flags cannot tell an old Skill's reply from a new
 * one. Every request carries the generation at launch, and a completion is only allowed to affect
 * the current Skill state when its generation still matches. Advancing the generation fences out
 * every outstanding open/goal/cancel at once. Pure and unit-testable.
 */
public final class SkillRequestFence {
    public SkillRequestFence() { }

    public static final class Request {
        public final int generation;
        public final String kind;        // "open" | "goal" | "cancel"
        public final String session;
        public final int revision;
        public final String epoch;       // null for open, which establishes the epoch
        public final long started;
        Request(int generation, String kind, String session, int revision, String epoch, long started) {
            this.generation = generation; this.kind = kind; this.session = session;
            this.revision = revision; this.epoch = epoch; this.started = started;
        }
    }

    public static final class Completion {
        public final Request request;
        public final JsonObject response;   // null means timeout / non-200 / transport failure
        public final long received;
        public Completion(Request request, JsonObject response, long received) {
            this.request = request; this.response = response; this.received = received;
        }
    }

    private int generation;

    public int generation() { return generation; }

    /** Invalidates every outstanding request. Called on new Skill, replace and session change. */
    public void advance() { generation += 1; }

    public Request open(String session, long now) { return new Request(generation, "open", session, -1, null, now); }
    public Request goal(String session, int revision, String epoch, long now) {
        return new Request(generation, "goal", session, revision, epoch, now);
    }
    public Request cancel(String session, int revision, String epoch, long now) {
        return new Request(generation, "cancel", session, revision, epoch, now);
    }

    public boolean current(Request request) { return request != null && request.generation == generation; }
    public boolean stale(Request request) { return !current(request); }

    /** A valid open ACK is a v2 envelope for the same session; it is what establishes the epoch. */
    public static boolean validOpenAck(Request request, JsonObject response) {
        if (request == null || response == null) { return false; }
        try {
            return ActionProtocol.integer(response, "version", 2)
                    && ActionProtocol.string(response, "session").equals(request.session)
                    && ActionProtocol.string(response, "daemonEpoch").length() > 0;
        } catch (RuntimeException e) { return false; }
    }

    /**
     * A valid cancel ACK must match the cancel request's session/revision/epoch and settle into
     * idle. Anything else (stale, null, 409, timeout) is not a completed handshake.
     */
    public static boolean validCancelAck(Request request, JsonObject response) {
        if (request == null || response == null) { return false; }
        try {
            SkillProtocol.validateView(response, request.session, request.revision, request.epoch);
            return "idle".equals(SkillProtocol.status(response));
        } catch (RuntimeException e) { return false; }
    }
}
