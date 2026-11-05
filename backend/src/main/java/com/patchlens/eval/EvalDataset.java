package com.patchlens.eval;

import com.patchlens.model.ChangedFile;
import com.patchlens.model.FindingCategory;
import com.patchlens.model.PullRequestMetadata;
import com.patchlens.sandbox.DirectorySnapshot;
import com.patchlens.sandbox.RepositorySnapshot;
import com.patchlens.sandbox.SafePaths;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;

/**
 * Loads a versioned, executable evaluation dataset from the classpath
 * ({@code eval/<datasetDir>/manifest.json} and {@code cases/<id>/...}).
 *
 * <p>Each case contains:
 * <ul>
 *   <li>{@code case.json} — PR title/body, changed files, labeled expected findings, reference fix</li>
 *   <li>{@code head/} — a complete, compilable Maven project at the PR head (its own tests pass)</li>
 *   <li>{@code oracle/} — hidden tests overlaid only for patch validation; they fail on the labeled
 *       bug and pass after a correct fix. The model never sees them.</li>
 * </ul>
 * The PR shown to the model consists of the {@code changedFiles} as newly added files, so the
 * diff's line numbers equal the head file's line numbers.
 */
public final class EvalDataset {

    public record ExpectedFinding(String id, FindingCategory category, String file,
                                  int lineStart, int lineEnd, boolean patchTarget, String description) {
        public EvalMatcher.Expected toMatcher() { return new EvalMatcher.Expected(id, category, file, lineStart, lineEnd); }
    }

    public record Case(String caseId, PullRequestMetadata metadata, List<ChangedFile> changedFiles,
                       List<ExpectedFinding> expectedFindings, String referencePatch,
                       Path headDir, Path oracleDir) {
        /** Snapshot used for patch validation: PR head with hidden oracle tests overlaid. */
        public RepositorySnapshot validationSnapshot(String datasetVersion) {
            List<Path> layers = new ArrayList<>(List.of(headDir));
            if (oracleDir != null) layers.add(oracleDir);
            return new DirectorySnapshot(caseId + "@" + datasetVersion, "eval-" + datasetVersion, layers);
        }
        public List<ExpectedFinding> patchTargets() {
            return expectedFindings.stream().filter(ExpectedFinding::patchTarget).toList();
        }
    }

    private final String version;
    private final String fingerprint;
    private final List<Case> cases;

    private EvalDataset(String version, String fingerprint, List<Case> cases) {
        this.version = version;
        this.fingerprint = fingerprint;
        this.cases = List.copyOf(cases);
    }

    public String version()     { return version; }
    /** SHA-256 over every dataset file (path + content), truncated — identifies the exact dataset. */
    public String fingerprint() { return fingerprint; }
    public List<Case> cases()   { return cases; }

    /**
     * @param datasetDir e.g. {@code "dataset-v2"}
     * @param workDir    directory the case trees are copied into (must be empty or absent)
     */
    public static EvalDataset load(String datasetDir, Path workDir, ObjectMapper mapper) throws IOException {
        String base = "eval/" + datasetDir + "/";
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();

        Resource manifestRes = resolver.getResource("classpath:" + base + "manifest.json");
        if (!manifestRes.exists()) throw new IOException("Dataset manifest not found: " + base + "manifest.json");
        JsonNode manifest = read(manifestRes, mapper);
        String version = manifest.path("datasetVersion").asString(datasetDir);

        // Copy every dataset file to disk (works from a jar too) and fingerprint it.
        Resource[] all = resolver.getResources("classpath*:" + base + "**");
        SortedMap<String, byte[]> files = new TreeMap<>();
        for (Resource r : all) {
            if (!r.isReadable()) continue;
            String url = r.getURL().toString();
            int at = url.indexOf(base);
            if (at < 0 || url.endsWith("/")) continue;
            String rel = url.substring(at + base.length());
            try (InputStream in = r.getInputStream()) {
                files.put(rel, in.readAllBytes());
            } catch (IOException e) {
                // directory entries on some resolvers are "readable" but not openable — skip
            }
        }
        Files.createDirectories(workDir);
        for (var e : files.entrySet()) {
            Path dest = SafePaths.resolveInside(workDir, e.getKey());
            Files.createDirectories(dest.getParent());
            Files.write(dest, e.getValue());
        }

        List<Case> cases = new ArrayList<>();
        for (JsonNode idNode : manifest.path("cases")) {
            String id = idNode.asString();
            Path caseDir = SafePaths.resolveInside(workDir, "cases/" + id);
            JsonNode c = mapper.readTree(Files.readString(caseDir.resolve("case.json")));
            Path head = caseDir.resolve("head");
            Path oracle = Files.isDirectory(caseDir.resolve("oracle")) ? caseDir.resolve("oracle") : null;

            List<ChangedFile> changed = new ArrayList<>();
            for (JsonNode f : c.path("changedFiles")) {
                String rel = f.asString();
                String content = Files.readString(SafePaths.resolveInside(head, rel), StandardCharsets.UTF_8);
                changed.add(asAddedFile(rel, content));
            }

            List<ExpectedFinding> expected = new ArrayList<>();
            for (JsonNode e : c.path("expectedFindings")) {
                expected.add(new ExpectedFinding(
                        e.path("id").asString(),
                        FindingCategory.valueOf(e.path("category").asString()),
                        e.path("file").asString(),
                        e.path("lineStart").asInt(),
                        e.path("lineEnd").asInt(),
                        e.path("patchTarget").asBoolean(false),
                        e.path("description").asString("")));
            }

            PullRequestMetadata metadata = new PullRequestMetadata("eval", id, 1,
                    c.path("title").asString(id), c.path("body").asString(""), "eval://" + version + "/" + id);
            cases.add(new Case(id, metadata, List.copyOf(changed), List.copyOf(expected),
                    c.path("referencePatch").asString(null), head, oracle));
        }
        return new EvalDataset(version, fingerprint(files), cases);
    }

    /** Represents a file added by the PR, in GitHub's per-file patch format. */
    static ChangedFile asAddedFile(String path, String content) {
        String body = content.endsWith("\n") ? content.substring(0, content.length() - 1) : content;
        String[] lines = body.split("\n", -1);
        StringBuilder patch = new StringBuilder("@@ -0,0 +1,").append(lines.length).append(" @@");
        for (String l : lines) patch.append("\n+").append(l);
        return new ChangedFile(path, "added", lines.length, 0, lines.length, patch.toString());
    }

    private static JsonNode read(Resource r, ObjectMapper mapper) throws IOException {
        try (InputStream in = r.getInputStream()) {
            return mapper.readTree(in);
        }
    }

    private static String fingerprint(SortedMap<String, byte[]> files) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (var e : files.entrySet()) {
                md.update(e.getKey().getBytes(StandardCharsets.UTF_8));
                md.update((byte) 0);
                md.update(e.getValue());
            }
            return HexFormat.of().formatHex(md.digest()).substring(0, 16);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
