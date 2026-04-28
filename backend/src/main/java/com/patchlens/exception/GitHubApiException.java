package com.patchlens.exception;

/**
 * Thrown when the GitHub API returns an error we need to surface to the caller.
 * errorCode maps to the error codes defined in the API design (e.g. GITHUB_RATE_LIMIT).
 */
public class GitHubApiException extends RuntimeException {

    private final String errorCode;

    public GitHubApiException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}