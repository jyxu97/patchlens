package com.patchlens.sandbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.regex.Pattern;

/**
 * Provides repository snapshots pinned to an exact commit SHA, downloaded from GitHub's
 * tarball endpoint ({@code GET /repos/{owner}/{repo}/tarball/{sha}}).
 *
 * <p>Archives are cached per (owner, repo, sha) because a SHA's content is immutable; every
 * validation attempt still extracts into its own fresh directory.
 */
@Service
public class GitHubSnapshotService {

    private static final Logger log = LoggerFactory.getLogger(GitHubSnapshotService.class);
    private static final Pattern SHA = Pattern.compile("^[0-9a-f]{40}$");
    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9._-]{1,100}$");
    private static final long MAX_ARCHIVE_BYTES = 256L * 1024 * 1024;

    private final SandboxProperties props;
    private final String githubToken;
    private final String apiBase;
    private final HttpClient http;

    public GitHubSnapshotService(SandboxProperties props,
                                 @Value("${github.token:}") String githubToken,
                                 @Value("${github.api-base:https://api.github.com}") String apiBase) {
        this.props = props;
        this.githubToken = githubToken;
        this.apiBase = apiBase;
        this.http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    public static boolean isFullSha(String sha) {
        return sha != null && SHA.matcher(sha).matches();
    }

    /** Returns a snapshot of {@code owner/repo} at {@code sha}. The archive is fetched lazily. */
    public RepositorySnapshot snapshot(String owner, String repo, String sha) {
        if (!NAME.matcher(owner).matches() || !NAME.matcher(repo).matches()) {
            throw new IllegalArgumentException("Invalid repository name: " + owner + "/" + repo);
        }
        if (!isFullSha(sha)) {
            throw new IllegalArgumentException("Snapshot requires a full 40-char commit SHA, got: " + sha);
        }
        return new GitHubTarballSnapshot(owner, repo, sha);
    }

    private final class GitHubTarballSnapshot implements RepositorySnapshot {
        private final String owner, repo, sha;

        GitHubTarballSnapshot(String owner, String repo, String sha) {
            this.owner = owner; this.repo = repo; this.sha = sha;
        }

        @Override public String revision()           { return sha; }
        @Override public String describe()           { return "github:" + owner + "/" + repo + "@" + sha; }
        @Override public String dependencyCacheKey() { return owner + "/" + repo; }

        @Override
        public void materialize(Path emptyDir) throws IOException {
            Path archive = cachedArchive();
            try (InputStream in = Files.newInputStream(archive)) {
                TarGzExtractor.Result r = TarGzExtractor.extract(in, emptyDir, TarGzExtractor.Limits.defaults());
                log.debug("Extracted {}: {} files, {} links skipped", describe(), r.filesWritten(), r.skippedLinks());
            }
        }

        private synchronized Path cachedArchive() throws IOException {
            Path dir = props.getCacheRoot().resolve("snapshots").resolve(owner).resolve(repo);
            Path archive = dir.resolve(sha + ".tar.gz");
            if (Files.isRegularFile(archive)) return archive;

            Files.createDirectories(dir);
            Path partial = Files.createTempFile(dir, sha, ".part");
            try {
                HttpRequest.Builder req = HttpRequest.newBuilder(
                                URI.create(apiBase + "/repos/" + owner + "/" + repo + "/tarball/" + sha))
                        .timeout(Duration.ofMinutes(5))
                        .header("Accept", "application/vnd.github+json")
                        .header("X-GitHub-Api-Version", "2022-11-28");
                if (githubToken != null && !githubToken.isBlank()) {
                    req.header("Authorization", "Bearer " + githubToken);
                }
                HttpResponse<InputStream> resp = http.send(req.GET().build(), HttpResponse.BodyHandlers.ofInputStream());
                if (resp.statusCode() != 200) {
                    resp.body().close();
                    throw new IOException("GitHub tarball download failed: HTTP " + resp.statusCode());
                }
                try (InputStream body = resp.body()) {
                    long copied = copyBounded(body, partial);
                    log.info("Downloaded snapshot {} ({} bytes)", describe(), copied);
                }
                Files.move(partial, archive, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                return archive;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while downloading snapshot", e);
            } finally {
                Files.deleteIfExists(partial);
            }
        }
    }

    private static long copyBounded(InputStream in, Path dest) throws IOException {
        try (var out = Files.newOutputStream(dest)) {
            byte[] buf = new byte[64 * 1024];
            long total = 0;
            int r;
            while ((r = in.read(buf)) > 0) {
                total += r;
                if (total > MAX_ARCHIVE_BYTES) throw new IOException("Snapshot archive exceeds " + MAX_ARCHIVE_BYTES + " bytes");
                out.write(buf, 0, r);
            }
            return total;
        }
    }
}
