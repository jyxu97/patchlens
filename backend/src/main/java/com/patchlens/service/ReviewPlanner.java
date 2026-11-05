package com.patchlens.service;

import com.patchlens.model.ChangedFile;
import com.patchlens.model.ReviewCoverage;
import com.patchlens.model.RiskScore;

import java.util.*;

/**
 * Decides, deterministically, which changed files are sent to the model and in which batches,
 * keeping prompt size (and therefore tokens and latency) bounded and observable.
 *
 * <ol>
 *   <li>Files without a text diff (binary / too large for GitHub to render) and deleted files
 *       are skipped — there is no new-side code to ground findings in.</li>
 *   <li>Remaining files are ordered by deterministic risk score (desc), then path.</li>
 *   <li>At most {@code maxFiles} are reviewed; each diff is truncated to {@code maxPatchChars}.</li>
 *   <li>Reviewed files are packed greedily into batches of at most {@code batchCharBudget}
 *       diff characters; at most {@code maxBatches} batches. Files that do not fit are skipped.</li>
 * </ol>
 * Every skipped or truncated file is reported in {@link ReviewCoverage}.
 */
public final class ReviewPlanner {

    public record Plan(List<List<ChangedFile>> batches, ReviewCoverage coverage) {
        /** Reviewed files as the model saw them (possibly truncated diffs). */
        public List<ChangedFile> reviewedFiles() {
            return batches.stream().flatMap(List::stream).toList();
        }
    }

    private ReviewPlanner() {}

    public static Plan plan(List<ChangedFile> files, List<RiskScore> riskScores,
                            int maxFiles, int maxPatchChars, int batchCharBudget, int maxBatches) {
        Map<String, Integer> risk = new HashMap<>();
        for (RiskScore rs : riskScores) risk.put(rs.filename(), rs.score());

        List<ReviewCoverage.SkippedFile> skipped = new ArrayList<>();
        List<ChangedFile> candidates = new ArrayList<>();
        for (ChangedFile f : files) {
            if ("removed".equals(f.status())) skipped.add(new ReviewCoverage.SkippedFile(f.filename(), ReviewCoverage.Reason.REMOVED));
            else if (f.patch() == null || f.patch().isBlank()) skipped.add(new ReviewCoverage.SkippedFile(f.filename(), ReviewCoverage.Reason.NO_TEXT_DIFF));
            else candidates.add(f);
        }
        candidates.sort(Comparator.<ChangedFile>comparingInt(f -> risk.getOrDefault(f.filename(), 0)).reversed()
                .thenComparing(ChangedFile::filename));

        List<List<ChangedFile>> batches = new ArrayList<>();
        List<String> truncated = new ArrayList<>();
        List<ChangedFile> current = new ArrayList<>();
        int currentChars = 0;
        int taken = 0;
        for (ChangedFile f : candidates) {
            if (taken >= maxFiles) { skipped.add(new ReviewCoverage.SkippedFile(f.filename(), ReviewCoverage.Reason.FILE_LIMIT)); continue; }
            ChangedFile prompt = f;
            if (f.patch().length() > maxPatchChars) {
                prompt = new ChangedFile(f.filename(), f.status(), f.additions(), f.deletions(), f.changes(),
                        f.patch().substring(0, maxPatchChars));
                truncated.add(f.filename());
            }
            int cost = prompt.patch().length();
            if (!current.isEmpty() && currentChars + cost > batchCharBudget) {
                batches.add(current);
                current = new ArrayList<>();
                currentChars = 0;
            }
            if (batches.size() >= maxBatches) {
                skipped.add(new ReviewCoverage.SkippedFile(f.filename(), ReviewCoverage.Reason.BATCH_LIMIT));
                truncated.remove(f.filename());
                continue;
            }
            current.add(prompt);
            currentChars += cost;
            taken++;
        }
        if (!current.isEmpty()) batches.add(current);

        List<String> reviewed = batches.stream().flatMap(List::stream).map(ChangedFile::filename).toList();
        ReviewCoverage coverage = new ReviewCoverage(files.size(), reviewed, List.copyOf(skipped), List.copyOf(truncated),
                batches.size(), maxFiles, maxPatchChars, null);
        return new Plan(batches.stream().map(List::copyOf).toList(), coverage);
    }
}
