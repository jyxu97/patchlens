package com.patchlens.sandbox;

import com.patchlens.service.ValidationConfig;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Loads a repository's {@code patchlens.yaml} build/test configuration from a pristine snapshot.
 *
 * <p>The file is untrusted input: it is parsed with SnakeYAML's {@link SafeConstructor} (no
 * arbitrary object construction), size-limited, and every timeout is clamped to the sandbox's
 * configured maximum. The commands themselves only ever execute inside the isolated container.
 *
 * <pre>
 * build:
 *   command: mvn -q -DskipTests test-compile
 * staticAnalysis:
 *   command: ""            # blank = disabled
 * tests:
 *   command: mvn -q test
 * timeouts:
 *   compileSeconds: 300
 *   staticAnalysisSeconds: 120
 *   testSeconds: 300
 * </pre>
 */
public final class ValidationConfigLoader {

    public static final List<String> CONFIG_FILE_NAMES = List.of(
            "patchlens.yaml", "patchlens.yml", ".patchlens.yaml", ".patchlens.yml");

    private static final long MAX_CONFIG_BYTES = 16 * 1024;
    private static final int MAX_COMMAND_CHARS = 1000;

    private ValidationConfigLoader() {}

    public record Loaded(ValidationConfig config, String source) {}

    public static Loaded load(Path workspaceRoot, int maxStepSeconds) throws IOException {
        for (String name : CONFIG_FILE_NAMES) {
            Path p = workspaceRoot.resolve(name);
            if (!Files.isRegularFile(p) || Files.isSymbolicLink(p)) continue;
            if (Files.size(p) > MAX_CONFIG_BYTES) {
                throw new IOException(name + " exceeds " + MAX_CONFIG_BYTES + " bytes");
            }
            Object parsed = new Yaml(new SafeConstructor(new LoaderOptions())).load(Files.readString(p));
            return new Loaded(fromMap(parsed instanceof Map<?, ?> m ? m : Map.of(), maxStepSeconds), name);
        }
        return new Loaded(clamp(ValidationConfig.defaults(), maxStepSeconds), "defaults");
    }

    static ValidationConfig fromMap(Map<?, ?> root, int maxStepSeconds) throws IOException {
        ValidationConfig defaults = ValidationConfig.defaults();
        ValidationConfig cfg = new ValidationConfig();

        cfg.setLanguage(string(root.get("language"), defaults.getLanguage()));
        cfg.setBuildFile(string(root.get("buildFile"), defaults.getBuildFile()));
        if (!SafePaths.isSafeRelative(cfg.getBuildFile())) throw new IOException("Unsafe buildFile: " + cfg.getBuildFile());

        cfg.setBuild(new ValidationConfig.Build(command(section(root, "build"), defaults.getBuild().command())));
        cfg.setStaticAnalysis(new ValidationConfig.StaticAnalysis(
                command(section(root, "staticAnalysis"), defaults.getStaticAnalysis().command())));
        cfg.setTests(new ValidationConfig.Tests(command(section(root, "tests"), defaults.getTests().command())));

        Map<?, ?> t = section(root, "timeouts");
        ValidationConfig.Timeouts dt = defaults.getTimeouts();
        cfg.setTimeouts(new ValidationConfig.Timeouts(
                integer(t.get("compileSeconds"), dt.compileSeconds()),
                integer(t.get("staticAnalysisSeconds"), dt.staticAnalysisSeconds()),
                integer(t.get("testSeconds"), dt.testSeconds())));
        return clamp(cfg, maxStepSeconds);
    }

    static ValidationConfig clamp(ValidationConfig cfg, int maxStepSeconds) {
        ValidationConfig.Timeouts t = cfg.getTimeouts();
        cfg.setTimeouts(new ValidationConfig.Timeouts(
                bound(t.compileSeconds(), maxStepSeconds),
                bound(t.staticAnalysisSeconds(), maxStepSeconds),
                bound(t.testSeconds(), maxStepSeconds)));
        return cfg;
    }

    private static int bound(int v, int max) { return Math.max(1, Math.min(v, max)); }

    private static Map<?, ?> section(Map<?, ?> root, String key) {
        return root.get(key) instanceof Map<?, ?> m ? m : Map.of();
    }

    private static String command(Map<?, ?> section, String fallback) throws IOException {
        if (!section.containsKey("command")) return fallback;
        String c = section.get("command") == null ? "" : section.get("command").toString();
        if (c.length() > MAX_COMMAND_CHARS) throw new IOException("Command exceeds " + MAX_COMMAND_CHARS + " chars");
        if (c.indexOf('\0') >= 0) throw new IOException("Command contains NUL byte");
        return c;
    }

    private static String string(Object v, String fallback) {
        return v == null ? fallback : v.toString();
    }

    private static int integer(Object v, int fallback) {
        if (v instanceof Number n) return n.intValue();
        if (v != null) {
            try { return Integer.parseInt(v.toString().trim()); } catch (NumberFormatException ignored) {}
        }
        return fallback;
    }
}
