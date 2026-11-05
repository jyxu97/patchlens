package com.patchlens.sandbox;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser and applier for unified diffs (the {@code -p1} "a/ b/" convention used by git).
 *
 * <p>Patches are applied in-process rather than by spawning {@code patch(1)} on the host, so
 * an untrusted diff can never be interpreted by a host tool: every target path is checked with
 * {@link SafePaths} and resolved strictly inside the workspace.
 *
 * <p>Application semantics are intentionally close to {@code patch --fuzz=0}: every context and
 * removed line must match exactly (a trailing {@code \r} is ignored), but a hunk may be found at
 * an offset from its declared position. Declared hunk line counts are not trusted; the hunk body
 * determines what is matched. Renames and binary patches are not supported.
 */
public final class UnifiedDiff {

    public static final String DEV_NULL = "/dev/null";

    private static final Pattern HUNK_HEADER =
            Pattern.compile("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*$");

    public record Hunk(int oldStart, int newStart, List<String> lines, boolean newMissingFinalNewline) {
        /** Lines the hunk expects to find in the original ("old") file. */
        List<String> oldLines() {
            List<String> out = new ArrayList<>();
            for (String l : lines) if (l.charAt(0) == ' ' || l.charAt(0) == '-') out.add(l.substring(1));
            return out;
        }
        /** Lines the hunk produces in the patched ("new") file. */
        List<String> newLines() {
            List<String> out = new ArrayList<>();
            for (String l : lines) if (l.charAt(0) == ' ' || l.charAt(0) == '+') out.add(l.substring(1));
            return out;
        }
    }

    public record FilePatch(String oldPath, String newPath, List<Hunk> hunks) {
        public boolean isNewFile()    { return DEV_NULL.equals(oldPath); }
        public boolean isDeletion()   { return DEV_NULL.equals(newPath); }
        public boolean isRename()     { return !isNewFile() && !isDeletion() && !oldPath.equals(newPath); }
        /** The repository-relative path this patch touches. */
        public String targetPath()    { return isDeletion() ? oldPath : newPath; }
    }

    /** Thrown when a diff cannot be parsed or cannot be applied to the workspace. */
    public static class PatchException extends Exception {
        public PatchException(String message) { super(message); }
    }

    private UnifiedDiff() {}

    // =====================================================================
    // Parsing
    // =====================================================================

    public static List<FilePatch> parse(String diffText) throws PatchException {
        if (diffText == null || diffText.isBlank()) throw new PatchException("Patch is empty");
        if (diffText.contains("GIT binary patch") || diffText.contains("Binary files ")) {
            throw new PatchException("Binary patches are not supported");
        }

        String[] lines = diffText.replace("\r\n", "\n").split("\n", -1);
        List<FilePatch> files = new ArrayList<>();
        int i = 0;
        while (i < lines.length) {
            String line = lines[i];
            if (!line.startsWith("--- ")) { i++; continue; }
            if (i + 1 >= lines.length || !lines[i + 1].startsWith("+++ ")) {
                throw new PatchException("Missing '+++' header after '" + line + "'");
            }
            String oldPath = parseHeaderPath(line.substring(4));
            String newPath = parseHeaderPath(lines[i + 1].substring(4));
            i += 2;

            List<Hunk> hunks = new ArrayList<>();
            while (i < lines.length && lines[i].startsWith("@@")) {
                Matcher m = HUNK_HEADER.matcher(lines[i]);
                if (!m.matches()) throw new PatchException("Malformed hunk header: " + lines[i]);
                int oldStart = Integer.parseInt(m.group(1));
                int newStart = Integer.parseInt(m.group(3));
                i++;

                List<String> body = new ArrayList<>();
                boolean newMissingNewline = false;
                int trailingSynthesizedBlanks = 0;
                while (i < lines.length) {
                    String b = lines[i];
                    if (b.startsWith("@@") || b.startsWith("--- ") || b.startsWith("diff --git")) break;
                    if (b.startsWith("\\")) {
                        // "\ No newline at end of file" applies to the preceding line
                        if (!body.isEmpty() && body.get(body.size() - 1).charAt(0) != '-') newMissingNewline = true;
                        i++;
                        continue;
                    }
                    if (b.isEmpty()) {
                        // Many generators drop the leading space on blank context lines.
                        body.add(" ");
                        trailingSynthesizedBlanks++;
                    } else {
                        char c = b.charAt(0);
                        if (c != ' ' && c != '+' && c != '-') break; // trailing prose / next header
                        body.add(stripCr(b));
                        trailingSynthesizedBlanks = 0;
                    }
                    i++;
                }
                // Raw empty lines at the very end of a hunk are separators (or end of text), not context.
                for (int k = 0; k < trailingSynthesizedBlanks; k++) body.remove(body.size() - 1);
                if (body.stream().noneMatch(s -> s.charAt(0) == '+' || s.charAt(0) == '-')) {
                    throw new PatchException("Hunk at -" + oldStart + " contains no changes");
                }
                hunks.add(new Hunk(oldStart, newStart, List.copyOf(body), newMissingNewline));
            }
            if (hunks.isEmpty()) throw new PatchException("File patch for " + newPath + " has no hunks");
            files.add(new FilePatch(oldPath, newPath, List.copyOf(hunks)));
        }
        if (files.isEmpty()) {
            throw new PatchException("No '---'/'+++' file headers found (invalid unified diff format)");
        }
        return files;
    }

    private static String parseHeaderPath(String raw) {
        String p = raw;
        int tab = p.indexOf('\t');
        if (tab >= 0) p = p.substring(0, tab);   // strip timestamp
        p = stripCr(p).strip();
        if (p.startsWith("\"") && p.endsWith("\"") && p.length() >= 2) p = p.substring(1, p.length() - 1);
        if (p.equals(DEV_NULL)) return DEV_NULL;
        if (p.startsWith("a/") || p.startsWith("b/")) p = p.substring(2); // -p1
        return p;
    }

    private static String stripCr(String s) {
        return s.endsWith("\r") ? s.substring(0, s.length() - 1) : s;
    }

    // =====================================================================
    // Application
    // =====================================================================

    /**
     * Applies all file patches to {@code root}. Either every file patch applies or an exception
     * is thrown; callers always apply to a fresh, disposable workspace, so a partial application
     * is never reused.
     */
    public static void apply(Path root, List<FilePatch> patches) throws PatchException, IOException {
        for (FilePatch fp : patches) {
            if (fp.isRename()) throw new PatchException("Renames are not supported: " + fp.oldPath() + " -> " + fp.newPath());
            Path target;
            try {
                target = SafePaths.resolveInside(root, fp.targetPath());
            } catch (IllegalArgumentException e) {
                throw new PatchException(e.getMessage());
            }
            if (Files.isSymbolicLink(target)) throw new PatchException("Refusing to patch symlink: " + fp.targetPath());

            if (fp.isNewFile()) {
                if (Files.exists(target)) throw new PatchException("File to create already exists: " + fp.targetPath());
                List<String> content = new ArrayList<>();
                boolean missingNl = false;
                for (Hunk h : fp.hunks()) {
                    if (!h.oldLines().isEmpty()) throw new PatchException("New-file hunk removes lines: " + fp.targetPath());
                    content.addAll(h.newLines());
                    missingNl = h.newMissingFinalNewline();
                }
                Files.createDirectories(target.getParent());
                Files.writeString(target, join(content, !missingNl), StandardCharsets.UTF_8);
                continue;
            }

            if (!Files.isRegularFile(target)) throw new PatchException("File to patch does not exist: " + fp.targetPath());
            FileText original = FileText.read(target);
            List<String> result = applyHunks(original.lines(), fp);

            if (fp.isDeletion()) {
                if (!result.isEmpty()) throw new PatchException("Deletion patch does not remove all lines of " + fp.targetPath());
                Files.delete(target);
            } else {
                boolean endsWithNewline = original.endsWithNewline();
                Hunk last = fp.hunks().get(fp.hunks().size() - 1);
                if (last.newMissingFinalNewline()) endsWithNewline = false;
                Files.writeString(target, join(result, endsWithNewline), StandardCharsets.UTF_8);
            }
        }
    }

    private static List<String> applyHunks(List<String> original, FilePatch fp) throws PatchException {
        List<String> out = new ArrayList<>();
        int cursor = 0;   // next unconsumed index in original
        int offset = 0;   // accumulated drift between declared and actual positions
        for (Hunk h : fp.hunks()) {
            List<String> expected = h.oldLines();
            int declared = Math.max(0, h.oldStart() - 1) + offset;
            int at = locate(original, expected, declared, cursor);
            if (at < 0) {
                throw new PatchException("Hunk @@ -" + h.oldStart() + " @@ does not match "
                        + fp.targetPath() + " (context or removed lines not found)");
            }
            out.addAll(original.subList(cursor, at));
            out.addAll(h.newLines());
            cursor = at + expected.size();
            offset = at - Math.max(0, h.oldStart() - 1);
        }
        out.addAll(original.subList(cursor, original.size()));
        return out;
    }

    /** Finds {@code expected} in {@code lines} at or after {@code minIndex}, nearest to {@code preferred}. */
    private static int locate(List<String> lines, List<String> expected, int preferred, int minIndex) {
        int maxStart = lines.size() - expected.size();
        if (maxStart < minIndex) return expected.isEmpty() ? Math.min(Math.max(preferred, minIndex), lines.size()) : -1;
        int start = Math.min(Math.max(preferred, minIndex), maxStart);
        for (int delta = 0; delta <= lines.size(); delta++) {
            int down = start + delta, up = start - delta;
            if (down <= maxStart && matchesAt(lines, expected, down)) return down;
            if (delta > 0 && up >= minIndex && matchesAt(lines, expected, up)) return up;
            if (down > maxStart && up < minIndex) break;
        }
        return -1;
    }

    private static boolean matchesAt(List<String> lines, List<String> expected, int at) {
        for (int k = 0; k < expected.size(); k++) {
            if (!lines.get(at + k).equals(expected.get(k))) return false;
        }
        return true;
    }

    private static String join(List<String> lines, boolean trailingNewline) {
        if (lines.isEmpty()) return "";
        return String.join("\n", lines) + (trailingNewline ? "\n" : "");
    }

    private record FileText(List<String> lines, boolean endsWithNewline) {
        static FileText read(Path p) throws IOException {
            String content = Files.readString(p, StandardCharsets.UTF_8).replace("\r\n", "\n");
            if (content.isEmpty()) return new FileText(new ArrayList<>(), true);
            boolean nl = content.endsWith("\n");
            String body = nl ? content.substring(0, content.length() - 1) : content;
            return new FileText(new ArrayList<>(List.of(body.split("\n", -1))), nl);
        }
    }
}
