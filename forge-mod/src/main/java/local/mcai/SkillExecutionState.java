package local.mcai;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Game-thread Skill authority: which action is outstanding and which receipts were already settled. */
public final class SkillExecutionState {
    public static final int MAX_LEDGER = 192;
    public String session, epoch, skillInstanceId;
    public int revision, sequence;
    public String pendingActionId;
    private final LinkedHashMap<String, String[]> ledger = new LinkedHashMap<String, String[]>();

    public void reset(String value) {
        session = value; epoch = null; skillInstanceId = null; revision = 0; sequence = 0; pendingActionId = null;
    }
    public boolean matches(String s, int r) { return session != null && session.equals(s) && revision == r; }

    /** Claims one outstanding action. A revision may hold a long sequence but never two at once. */
    public boolean claim(String actionId, int actionSequence) {
        if (pendingActionId != null || ledger.containsKey(actionId) || actionSequence <= sequence) { return false; }
        pendingActionId = actionId; sequence = actionSequence; return true;
    }
    public void complete(String status, String reason, int count) {
        if (pendingActionId == null) { return; }
        ledger.put(pendingActionId, new String[] {status, reason, Integer.toString(count)});
        while (ledger.size() > MAX_LEDGER) {
            Iterator<Map.Entry<String, String[]>> it = ledger.entrySet().iterator();
            it.next(); it.remove();
        }
        pendingActionId = null;
    }
    public boolean known(String actionId) { return ledger.containsKey(actionId); }
    public String[] outcome(String actionId) { return ledger.get(actionId); }
    public boolean outstanding() { return pendingActionId != null; }
}
