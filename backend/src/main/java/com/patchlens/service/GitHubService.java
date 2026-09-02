package com.patchlens.service;

import com.patchlens.exception.GitHubApiException;
import com.patchlens.model.ChangedFile;
import com.patchlens.model.PullRequestMetadata;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

@Service
public class GitHubService {

    private final RestClient restClient;

    // @Qualifier tells Spring which RestClient bean to inject,
    // since we may have multiple RestClient beans in the future
    public GitHubService(@Qualifier("githubRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    /**
     * Fetches PR title, body, and URL from GitHub.
     * Calls: GET /repos/{owner}/{repo}/pulls/{pullNumber}
     */
    public PullRequestMetadata fetchMetadata(String owner, String repo, int pullNumber) {
        JsonNode response = restClient.get()
                .uri("/repos/{owner}/{repo}/pulls/{pull}", owner, repo, pullNumber)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {
                    if (res.getStatusCode().value() == 404) {
                        throw new GitHubApiException("PR_NOT_FOUND",
                                "Pull request not found: " + owner + "/" + repo + "#" + pullNumber);
                    }
                    if (res.getStatusCode().value() == 403) {
                        throw new GitHubApiException("GITHUB_RATE_LIMIT",
                                "GitHub API rate limit exceeded. Configure a GitHub token to increase limits.");
                    }
                    throw new GitHubApiException("GITHUB_API_ERROR",
                            "GitHub API error: " + res.getStatusCode());
                })
                .body(JsonNode.class);

        return new PullRequestMetadata(
                owner,
                repo,
                pullNumber,
                response.get("title").asString(),
                response.path("body").asString(""),  // body can be null on GitHub
                response.get("html_url").asString()
        );
    }

    /**
     * Fetches the list of changed files for a PR.
     * Calls: GET /repos/{owner}/{repo}/pulls/{pullNumber}/files
     *
     * GitHub returns up to 100 files per page. For the MVP we fetch only page 1,
     * which is enough for the configured MAX_CHANGED_FILES=20 limit.
     */
    public List<ChangedFile> fetchChangedFiles(String owner, String repo, int pullNumber) {
        JsonNode response = restClient.get()
                .uri("/repos/{owner}/{repo}/pulls/{pull}/files?per_page=100",
                        owner, repo, pullNumber)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {
                    if (res.getStatusCode().value() == 403) {
                        throw new GitHubApiException("GITHUB_RATE_LIMIT",
                                "GitHub API rate limit exceeded.");
                    }
                    throw new GitHubApiException("GITHUB_API_ERROR",
                            "GitHub API error: " + res.getStatusCode());
                })
                .body(JsonNode.class);

        List<ChangedFile> files = new ArrayList<>();
        for (JsonNode fileNode : response) {
            files.add(new ChangedFile(
                    fileNode.get("filename").asString(),
                    fileNode.get("status").asString(),
                    fileNode.get("additions").asInt(),
                    fileNode.get("deletions").asInt(),
                    fileNode.get("changes").asInt(),
                    // patch is absent for binary files or when the diff is too large
                    fileNode.has("patch") ? fileNode.get("patch").asString(null) : null
            ));
        }
        return files;
    }

    /**
     * Fetches the raw byte content of a file at a specific ref (branch name or commit SHA).
     * Calls: GET /repos/{owner}/{repo}/contents/{path}?ref={ref}
     *
     * @return the decoded file bytes, or empty if the file does not exist at that ref (404)
     * @throws GitHubApiException for non-404 API errors
     */
    public Optional<byte[]> fetchFileContent(String owner, String repo, String path, String ref) {
        // Build URI manually — path may contain slashes that must not be percent-encoded as %2F
        String uri = "/repos/" + owner + "/" + repo + "/contents/" + path + "?ref=" + ref;

        try {
            JsonNode response = restClient.get()
                    .uri(uri)
                    .retrieve()
                    .onStatus(status -> status.value() == 404, (req, res) -> {
                        // File does not exist at this ref (e.g. added by the PR) — not an error
                        throw new FileNotFoundException404();
                    })
                    .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {
                        if (res.getStatusCode().value() == 403) {
                            throw new GitHubApiException("GITHUB_RATE_LIMIT",
                                    "GitHub API rate limit exceeded.");
                        }
                        throw new GitHubApiException("GITHUB_API_ERROR",
                                "GitHub API error fetching file: " + res.getStatusCode());
                    })
                    .body(JsonNode.class);

            // GitHub returns content as base64 with newlines embedded — strip whitespace before decoding
            String encoded = response.get("content").asString().replaceAll("\\s", "");
            return Optional.of(Base64.getDecoder().decode(encoded));

        } catch (FileNotFoundException404 e) {
            return Optional.empty();
        }
    }

    /** Sentinel exception used to signal a 404 response from the Contents API. */
    private static class FileNotFoundException404 extends RuntimeException {}
}