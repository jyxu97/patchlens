package com.patchlens.service;

import com.patchlens.model.ChangedFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Deterministic post-processing applied to model findings before they are persisted.
 *
 * <p>Three different levels of trust are kept separate:
 * <ol>
 *   <li><b>Syntactic validity</b> (here) — required structured fields are present and well formed.</li>
 *   <li><b>Repository grounding</b> (here) — the file is part of the reviewed PR, is not deleted,
 *       and the reported line range lies on lines that actually appear in that file's diff
 *       (new side of a hunk). Evidence citations outside the diff are stripped.</li>
 *   <li><b>Behavioral validation</b> (not here) — whether a suggested fix actually compiles and
 *       passes tests is decided only by the sandbox ({@code PatchValidationService}).</li>
 * </ol>
 * Passing this filter means a finding is well formed and grounded — not that it is correct.
 *
 * <p>Then, deterministically: findings below {@value #MIN_CONFIDENCE} confidence are dropped;
 * duplicates (same file, same category, overlapping lines) keep the highest-confidence one; at
 * most {@value #MAX_FINDINGS_PER_PR} findings are kept. Output order is stable (file, line).
 */
@Service
public class FindingFilterService {

    private static final Logger log = LoggerFactory.getLogger(FindingFilterService.class);

    static final double MIN_CONFIDENCE = 0.3;
    static final int MAX_FINDINGS_PER_PR = 10;

    public enum Rejection { MISSING_FIELDS, FILE_NOT_IN_PR, FILE_DELETED, NO_DIFF_FOR_FILE,
                            INVALID_LINE_RANGE, LINES_OUTSIDE_DIFF, LOW_CONFIDENCE, DUPLICATE, OVER_CAP }

    public record Rejected(OpenAIService.DetectedFinding finding, Rejection reason) {}

    public record Report(List<OpenAIService.DetectedFinding> accepted, List<Rejected> rejected, int evidenceStripped) {
        public long count(Rejection r) { return rejected.stream().filter(x -> x.reason() == r).count(); }
    }

    public List<OpenAIService.DetectedFinding> filter(List<OpenAIService.DetectedFinding> findings,
                                                      List<ChangedFile> changedFiles) {
        return filterWithReport(findings, changedFiles).accepted();
    }

    public Report filterWithReport(List<OpenAIService.DetectedFinding> findings, List<ChangedFile> changedFiles) {
        Map<String, ChangedFile> files = new HashMap<>();
        for (ChangedFile cf : changedFiles) files.put(cf.filename(), cf);
        Map<String, DiffHunks> hunks = new HashMap<>();

        List<Rejected> rejected = new ArrayList<>();
        List<OpenAIService.DetectedFinding> grounded = new ArrayList<>();
        int[] stripped = {0};

        for (OpenAIService.DetectedFinding f : findings) {
            Rejection r = check(f, files, hunks);
            if (r != null) { rejected.add(new Rejected(f, r)); continue; }
            grounded.add(withGroundedEvidence(f, files, hunks, stripped));
        }

        // Deterministic priority: confidence desc, severity desc, then position and title.
        grounded.sort(Comparator
                .comparingDouble(OpenAIService.DetectedFinding::confidence).reversed()
                .thenComparing(f -> f.severity().ordinal(), Comparator.reverseOrder())
                .thenComparing(OpenAIService.DetectedFinding::file)
                .thenComparingInt(OpenAIService.DetectedFinding::startLine)
                .thenComparing(OpenAIService.DetectedFinding::title));

        List<OpenAIService.DetectedFinding> accepted = new ArrayList<>();
        for (OpenAIService.DetectedFinding f : grounded) {
            if (accepted.stream().anyMatch(a -> isDuplicate(a, f))) { rejected.add(new Rejected(f, Rejection.DUPLICATE)); continue; }
            if (accepted.size() >= MAX_FINDINGS_PER_PR) { rejected.add(new Rejected(f, Rejection.OVER_CAP)); continue; }
            accepted.add(f);
        }
        accepted.sort(Comparator.comparing(OpenAIService.DetectedFinding::file)
                .thenComparingInt(OpenAIService.DetectedFinding::startLine));

        if (!rejected.isEmpty() || stripped[0] > 0) {
            Map<Rejection, Long> byReason = new EnumMap<>(Rejection.class);
            rejected.forEach(x -> byReason.merge(x.reason(), 1L, Long::sum));
            log.info("Finding filter: {} accepted, {} rejected {}, {} evidence citation(s) stripped",
                    accepted.size(), rejected.size(), byReason, stripped[0]);
        }
        return new Report(List.copyOf(accepted), List.copyOf(rejected), stripped[0]);
    }

    private Rejection check(OpenAIService.DetectedFinding f, Map<String, ChangedFile> files, Map<String, DiffHunks> hunks) {
        // 1 — syntactic validity
        if (f.file() == null || f.file().isBlank() || f.title() == null || f.title().isBlank()
                || f.explanation() == null || f.explanation().isBlank()
                || f.category() == null || f.severity() == null) {
            return Rejection.MISSING_FIELDS;
        }
        // 2 — repository grounding
        ChangedFile cf = files.get(f.file());
        if (cf == null) return Rejection.FILE_NOT_IN_PR;
        if ("removed".equals(cf.status())) return Rejection.FILE_DELETED;
        int start = f.startLine();
        int end = f.endLine() <= 0 ? start : f.endLine();
        if (start < 1 || end < start) return Rejection.INVALID_LINE_RANGE;
        DiffHunks h = hunks.computeIfAbsent(f.file(), k -> DiffHunks.parse(cf.patch()));
        if (h.isEmpty()) return Rejection.NO_DIFF_FOR_FILE;
        if (!h.overlaps(start, end)) return Rejection.LINES_OUTSIDE_DIFF;
        // 3 — confidence
        if (f.confidence() < MIN_CONFIDENCE) return Rejection.LOW_CONFIDENCE;
        return null;
    }

    /** Removes evidence citations that point outside the PR or outside the cited file's diff. */
    private OpenAIService.DetectedFinding withGroundedEvidence(OpenAIService.DetectedFinding f,
                                                               Map<String, ChangedFile> files,
                                                               Map<String, DiffHunks> hunks, int[] stripped) {
        if (f.evidence() == null || f.evidence().isEmpty()) return f;
        List<OpenAIService.Evidence> kept = new ArrayList<>();
        for (OpenAIService.Evidence e : f.evidence()) {
            String file = e.file() == null || e.file().isBlank() ? f.file() : e.file();
            ChangedFile cf = files.get(file);
            boolean ok = cf != null && hunks.computeIfAbsent(file, k -> DiffHunks.parse(cf.patch())).contains(e.line());
            if (ok) kept.add(e); else stripped[0]++;
        }
        if (kept.size() == f.evidence().size()) return f;
        return new OpenAIService.DetectedFinding(f.file(), f.startLine(), f.endLine(), f.category(), f.severity(),
                f.title(), f.explanation(), List.copyOf(kept), f.confidence());
    }

    private static boolean isDuplicate(OpenAIService.DetectedFinding a, OpenAIService.DetectedFinding b) {
        if (!a.file().equals(b.file()) || a.category() != b.category()) return false;
        int aEnd = a.endLine() > 0 ? a.endLine() : a.startLine();
        int bEnd = b.endLine() > 0 ? b.endLine() : b.startLine();
        return b.startLine() <= aEnd && bEnd >= a.startLine();
    }
}
