package com.patchlens.exception;

/**
 * Thrown when the GitHub API returns an error we need to surface to the caller.
 * errorCode maps to the error codes defined in the API design (e.g. GITHUB_RATE_LIMIT).
 */
public class GitHubApiException extends RuntimeException {

    private final String errorCode;
    /** HTTP status of the failed response, or -1 if no response was received (timeout, I/O). */
    private final int httpStatus;

    public GitHubApiException(String errorCode, String message) {
        this(errorCode, message, -1);
    }

    public GitHubApiException(String errorCode, String message, int httpStatus) {
        super(message);
        this.errorCode = errorCode;
        this.httpStatus = httpStatus;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    /** Transient failures worth retrying: no response, 5xx, 429, or 403 rate limiting. */
    public boolean isRetryable() {
        return httpStatus < 0 || httpStatus >= 500 || httpStatus == 429
                || (httpStatus == 403 && "GITHUB_RATE_LIMIT".equals(errorCode));
    }
}
