package com.patchlens.service;

import com.patchlens.model.ChangedFile;
import com.patchlens.model.RiskScore;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class RiskScoringService {

    /**
     * Scores every changed file and returns the results sorted by score descending.
     * Rules are applied deterministically — no AI involved at this stage.
     */
    public List<RiskScore> score(List<ChangedFile> files) {
        // PR-level check: does this PR include any test files?
        boolean hasTestFiles = files.stream().anyMatch(f -> isTestFile(f.filename()));

        return files.stream()
                .map(file -> scoreFile(file, hasTestFiles))
                .sorted((a, b) -> b.score() - a.score())
                .toList();
    }

    /**
     * Computes the overall PR risk level as the highest risk level among all files.
     */
    public RiskScore.RiskLevel overallRisk(List<RiskScore> scores) {
        return scores.stream()
                .map(RiskScore::riskLevel)
                .max((a, b) -> a.ordinal() - b.ordinal())
                .orElse(RiskScore.RiskLevel.low);
    }

    // --- per-file scoring ---

    private RiskScore scoreFile(ChangedFile file, boolean hasTestFiles) {
        int score = 0;
        List<String> reasons = new ArrayList<>();
        String path = file.filename().toLowerCase();

        // +3 for authentication / session related paths
        if (containsAny(path, "auth", "login", "token", "session", "password", "credential")) {
            score += 3;
            reasons.add("touches authentication or session logic");
        }

        // +3 for payment related paths
        if (containsAny(path, "payment", "billing", "checkout", "invoice", "subscription")) {
            score += 3;
            reasons.add("touches payment or billing logic");
        }

        // +2 for database migrations
        if (containsAny(path, "migration", "flyway", "liquibase", "changelog") ||
                file.filename().matches(".*V\\d+__.*\\.sql")) {
            score += 2;
            reasons.add("database migration file");
        }

        // +2 for application config files
        if (containsAny(path, "application.properties", "application.yml", "application.yaml") ||
                containsAny(path, "config/", "configuration")) {
            score += 2;
            reasons.add("application configuration change");
        }

        // +2 for large changes
        int totalLines = file.additions() + file.deletions();
        if (totalLines > 300) {
            score += 2;
            reasons.add("large change (" + totalLines + " lines)");
        }

        // +1 for cache / concurrency related paths
        if (containsAny(path, "cache", "redis", "queue", "async", "thread", "concurrent")) {
            score += 1;
            reasons.add("touches caching or concurrency");
        }

        // +1 if the PR has no test files at all (PR-level signal, applied to every file)
        if (!hasTestFiles) {
            score += 1;
            reasons.add("no test files in this PR");
        }

        return new RiskScore(file.filename(), score, RiskScore.toLevel(score), reasons);
    }

    // --- helpers ---

    private boolean containsAny(String path, String... keywords) {
        for (String keyword : keywords) {
            if (path.contains(keyword)) return true;
        }
        return false;
    }

    private boolean isTestFile(String filename) {
        String lower = filename.toLowerCase();
        return lower.contains("/test/") || lower.endsWith("test.java")
                || lower.endsWith("tests.java") || lower.endsWith("spec.java")
                || lower.contains("test/") || lower.endsWith(".spec.ts")
                || lower.endsWith(".test.ts");
    }
}