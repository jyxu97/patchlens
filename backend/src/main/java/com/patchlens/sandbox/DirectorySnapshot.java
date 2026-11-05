package com.patchlens.sandbox;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Snapshot backed by one or more local directory trees, copied in order (later layers overwrite
 * earlier ones). Used for evaluation fixtures, where a case's {@code head/} tree can be overlaid
 * with hidden {@code oracle/} tests that the model never sees.
 *
 * <p>Symlinks inside the source trees are not followed or copied.
 */
public final class DirectorySnapshot implements RepositorySnapshot {

    private final String revision;
    private final String cacheKey;
    private final List<Path> layers;

    public DirectorySnapshot(String revision, String cacheKey, List<Path> layers) {
        this.revision = revision;
        this.cacheKey = cacheKey;
        this.layers = List.copyOf(layers);
    }

    @Override public String revision()           { return revision; }
    @Override public String dependencyCacheKey() { return cacheKey; }
    @Override public String describe()           { return "directory:" + revision; }

    @Override
    public void materialize(Path emptyDir) throws IOException {
        Files.createDirectories(emptyDir);
        for (Path layer : layers) {
            if (!Files.isDirectory(layer)) throw new IOException("Snapshot layer missing: " + layer);
            List<Path> entries = new ArrayList<>();
            try (Stream<Path> walk = Files.walk(layer)) {
                walk.filter(p -> !Files.isSymbolicLink(p)).forEach(entries::add);
            }
            for (Path src : entries) {
                String rel = layer.relativize(src).toString().replace('\\', '/');
                if (rel.isEmpty()) continue;
                Path dest = SafePaths.resolveInside(emptyDir, rel);
                if (Files.isDirectory(src)) {
                    Files.createDirectories(dest);
                } else if (Files.isRegularFile(src)) {
                    Files.createDirectories(dest.getParent());
                    try (InputStream in = Files.newInputStream(src)) {
                        Files.copy(in, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        }
    }
}
