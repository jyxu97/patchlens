package com.patchlens.eval;

import com.patchlens.model.FindingCategory;

import java.util.*;

/**
 * Deterministic one-to-one matching between detected findings and labeled expected findings.
 *
 * <p><b>Match rule.</b> A detected finding is <em>compatible</em> with an expected finding iff
 * <ol>
 *   <li>the repository-relative file paths are identical,</li>
 *   <li>the categories are equal, and</li>
 *   <li>the detected line range overlaps the expected range widened by
 *       {@value #LINE_TOLERANCE} lines on each side (ranges are inclusive; an end line ≤ 0 is
 *       treated as equal to the start line).</li>
 * </ol>
 * Among compatible pairs a <em>maximum-cardinality</em> one-to-one assignment is chosen
 * (Kuhn's augmenting-path algorithm), so one expected issue can never yield more than one true
 * positive and two near-duplicate detections cannot both claim it. Expected findings are
 * processed in id order and candidates in detection order, making the result deterministic.
 *
 * <p>TP = matched pairs, FP = unmatched detections, FN = unmatched expected findings.
 */
public final class EvalMatcher {

    public static final int LINE_TOLERANCE = 2;

    public record Expected(String id, FindingCategory category, String file, int lineStart, int lineEnd) {}
    public record Detected(int index, FindingCategory category, String file, int lineStart, int lineEnd) {}
    public record Match(String expectedId, int detectedIndex) {}

    public record Result(List<Match> matches, int truePositives, int falsePositives, int falseNegatives) {
        public Optional<Integer> detectedFor(String expectedId) {
            return matches.stream().filter(m -> m.expectedId().equals(expectedId)).map(Match::detectedIndex).findFirst();
        }
    }

    private EvalMatcher() {}

    public static boolean compatible(Expected e, Detected d) {
        if (e.category() != d.category()) return false;
        if (!normalize(e.file()).equals(normalize(d.file()))) return false;
        int ds = d.lineStart(), de = d.lineEnd() > 0 ? Math.max(d.lineEnd(), ds) : ds;
        int es = e.lineStart() - LINE_TOLERANCE, ee = Math.max(e.lineEnd(), e.lineStart()) + LINE_TOLERANCE;
        return ds <= ee && de >= es;
    }

    public static Result match(List<Expected> expected, List<Detected> detected) {
        List<Expected> exp = expected.stream().sorted(Comparator.comparing(Expected::id)).toList();
        List<Detected> det = detected.stream().sorted(Comparator.comparingInt(Detected::index)).toList();

        List<List<Integer>> adj = new ArrayList<>();
        for (Expected e : exp) {
            List<Integer> candidates = new ArrayList<>();
            for (int j = 0; j < det.size(); j++) if (compatible(e, det.get(j))) candidates.add(j);
            adj.add(candidates);
        }

        int[] ownerOfDetected = new int[det.size()];
        Arrays.fill(ownerOfDetected, -1);
        for (int i = 0; i < exp.size(); i++) {
            augment(i, adj, ownerOfDetected, new boolean[det.size()]);
        }

        List<Match> matches = new ArrayList<>();
        for (int j = 0; j < det.size(); j++) {
            if (ownerOfDetected[j] >= 0) matches.add(new Match(exp.get(ownerOfDetected[j]).id(), det.get(j).index()));
        }
        matches.sort(Comparator.comparing(Match::expectedId));
        int tp = matches.size();
        return new Result(List.copyOf(matches), tp, det.size() - tp, exp.size() - tp);
    }

    private static boolean augment(int i, List<List<Integer>> adj, int[] owner, boolean[] seen) {
        for (int j : adj.get(i)) {
            if (seen[j]) continue;
            seen[j] = true;
            if (owner[j] < 0 || augment(owner[j], adj, owner, seen)) {
                owner[j] = i;
                return true;
            }
        }
        return false;
    }

    private static String normalize(String path) {
        if (path == null) return "";
        String p = path.strip().replace('\\', '/');
        while (p.startsWith("./")) p = p.substring(2);
        while (p.startsWith("/")) p = p.substring(1);
        if (p.startsWith("a/") || p.startsWith("b/")) p = p.substring(2);
        return p;
    }
}
