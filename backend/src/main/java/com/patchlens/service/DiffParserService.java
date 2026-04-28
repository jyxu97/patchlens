package com.patchlens.service;

import com.patchlens.model.ChangedFile;
import com.patchlens.model.PullRequestMetadata;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

@Service
public class DiffParserService {

    /**
     * Builds a single normalized string from the PR metadata and all changed files.
     * This string is used both for hashing (cache key) and as part of the AI prompt.
     *
     * Using a consistent format ensures the same PR always produces the same hash,
     * even if the GitHub API returns files in a different order.
     */
    public String normalize(PullRequestMetadata metadata, List<ChangedFile> files) {
        StringBuilder sb = new StringBuilder();

        sb.append("TITLE: ").append(metadata.title()).append("\n");
        sb.append("BODY: ").append(metadata.body()).append("\n\n");

        // Sort files by filename for a stable ordering
        List<ChangedFile> sorted = files.stream()
                .sorted((a, b) -> a.filename().compareTo(b.filename()))
                .toList();

        for (ChangedFile file : sorted) {
            sb.append("FILE: ").append(file.filename()).append("\n");
            sb.append("STATUS: ").append(file.status()).append("\n");
            sb.append("ADDITIONS: ").append(file.additions()).append("\n");
            sb.append("DELETIONS: ").append(file.deletions()).append("\n");
            if (file.patch() != null) {
                sb.append("PATCH:\n").append(file.patch()).append("\n");
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    /**
     * Computes a SHA-256 hash of the normalized diff string.
     * Used as the Redis cache key — if the diff hasn't changed, the hash is the same,
     * and we can return the cached review without calling the AI again.
     */
    public String hash(String normalizedDiff) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(normalizedDiff.getBytes(StandardCharsets.UTF_8));
            // Convert raw bytes to a readable hex string, e.g. "a3f1c2..."
            return HexFormat.of().formatHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed to exist in all Java implementations
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}