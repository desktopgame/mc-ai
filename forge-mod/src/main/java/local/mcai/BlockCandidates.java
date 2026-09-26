package local.mcai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Bounded, type-fair block candidate selection.
 *
 * Keeps at most the nearest {@link #PER_TYPE} per block type while streaming, then returns the
 * globally nearest {@link #GLOBAL_LIMIT}. Type-fairness matters: a global nearest-N would let common
 * ground (dirt) crowd out a nearby log or ore. Pure and unit-testable; no voxel map is retained.
 */
public final class BlockCandidates {
    public static final int PER_TYPE = 4;
    public static final int GLOBAL_LIMIT = 32;

    public static final class Candidate {
        public final int x, y, z;
        public final double distance;
        public final String type;
        public Candidate(int x, int y, int z, double distance, String type) {
            this.x = x; this.y = y; this.z = z; this.distance = distance; this.type = type;
        }
    }

    private final Map<String, List<Candidate>> byType = new HashMap<String, List<Candidate>>();

    /** Offers one already exposure-filtered candidate; keeps only the nearest PER_TYPE of its type. */
    public void offer(Candidate candidate) {
        List<Candidate> list = byType.get(candidate.type);
        if (list == null) { list = new ArrayList<Candidate>(); byType.put(candidate.type, list); }
        if (list.size() < PER_TYPE) { list.add(candidate); return; }
        int farthest = 0;
        for (int i = 1; i < list.size(); i++) { if (list.get(i).distance > list.get(farthest).distance) { farthest = i; } }
        if (candidate.distance < list.get(farthest).distance) { list.set(farthest, candidate); }
    }

    /** Flattens the per-type winners and returns the globally nearest up to GLOBAL_LIMIT. */
    public List<Candidate> select() {
        List<Candidate> all = new ArrayList<Candidate>();
        for (List<Candidate> list : byType.values()) { all.addAll(list); }
        Collections.sort(all, new Comparator<Candidate>() {
            @Override public int compare(Candidate a, Candidate b) { return Double.compare(a.distance, b.distance); }
        });
        return new ArrayList<Candidate>(all.subList(0, Math.min(GLOBAL_LIMIT, all.size())));
    }
}
