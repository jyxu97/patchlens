package com.patchlens.util;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Removes credential-shaped substrings from text before it is logged or persisted
 * (sandbox output, exception messages, error fields returned by the API).
 */
public final class Redactor {

    private static final List<Pattern> PATTERNS = List.of(
            Pattern.compile("gh[pousr]_[A-Za-z0-9]{20,}"),            // GitHub tokens
            Pattern.compile("github_pat_[A-Za-z0-9_]{20,}"),          // GitHub fine-grained PATs
            Pattern.compile("sk-[A-Za-z0-9_-]{20,}"),                 // OpenAI-style keys
            Pattern.compile("AKIA[0-9A-Z]{16}"),                      // AWS access key ids
            Pattern.compile("(?i)(bearer|token|authorization)[\"':= ]+[A-Za-z0-9._~+/=-]{12,}"),
            Pattern.compile("(?i)(password|passwd|secret)[\"':= ]+[^\\s\"']{4,}"));

    private Redactor() {}

    public static String redact(String text) {
        if (text == null || text.isEmpty()) return text;
        String out = text;
        for (Pattern p : PATTERNS) out = p.matcher(out).replaceAll("[REDACTED]");
        return out;
    }

    /** Redacts and truncates to at most {@code maxChars}, keeping the tail (where errors usually are). */
    public static String redactTail(String text, int maxChars) {
        String r = redact(text == null ? "" : text);
        return r.length() <= maxChars ? r : "...(truncated)\n" + r.substring(r.length() - maxChars);
    }
}
