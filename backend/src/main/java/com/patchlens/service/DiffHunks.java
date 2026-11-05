package com.patchlens.service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * New-side ("head") line coverage of one file's diff, parsed from GitHub's per-file
 * {@code patch} text. Used to ground model-reported line numbers in the code actually reviewed.
 */
public final class DiffHunks {

    private static final Pattern HUNK = Pattern.compile("^@@ -\\d+(?:,\\d+)? \\+(\\d+)(?:,(\\d+))? @@");

    /** Inclusive new-side line range of one hunk. */
    public record Range(int start, int end) {
        public boolean overlaps(int s, int e) { return s <= end && e >= start; }
        public boolean contains(int line)     { return line >= start && line <= end; }
    }

    private final List<Range> ranges;
    private final int maxLine;

    private DiffHunks(List<Range> ranges) {
        this.ranges = List.copyOf(ranges);
        this.maxLine = ranges.stream().mapToInt(Range::end).max().orElse(0);
    }

    public static DiffHunks parse(String patch) {
        List<Range> ranges = new ArrayList<>();
        if (patch == null) return new DiffHunks(ranges);
        for (String line : patch.split("\n")) {
            Matcher m = HUNK.matcher(line);
            if (!m.find()) continue;
            int start = Integer.parseInt(m.group(1));
            int count = m.group(2) == null ? 1 : Integer.parseInt(m.group(2));
            if (count > 0) ranges.add(new Range(start, start + count - 1));
        }
        return new DiffHunks(ranges);
    }

    public List<Range> ranges()     { return ranges; }
    public boolean isEmpty()        { return ranges.isEmpty(); }
    public int maxLine()            { return maxLine; }

    /** True if the inclusive range [start, end] touches at least one hunk. */
    public boolean overlaps(int start, int end) {
        return ranges.stream().anyMatch(r -> r.overlaps(start, end));
    }

    public boolean contains(int line) {
        return ranges.stream().anyMatch(r -> r.contains(line));
    }
}
