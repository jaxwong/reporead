package com.reporead;

/**
 * An expected, typed failure published to API clients as {code, message}. Messages never contain credentials or note
 * content, and name no client's screens or controls: each client words a code for its own users.
 */
public final class ApiFailure extends RuntimeException {
    /** Every code the API publishes, each with the one HTTP status it is published with. */
    public enum Code {
        INVALID_REQUEST(400),
        INVALID_ANNOTATION(400),
        INVALID_CODE_CHALLENGE(400),
        INVALID_IMAGE_PATH(400),
        INVALID_READING_STATE(400),
        INVALID_REPOSITORY(400),
        INVALID_REVIEW(400),
        INVALID_SIGN_IN_CODE(400),
        INVALID_VERSIONS(400),
        SIGN_IN_REQUIRED(401),
        GITHUB_ACCESS_DENIED(403),
        REPOSITORY_NOT_AUTHORIZED(403),
        NOT_FOUND(404),
        DEFAULT_BRANCH_NOT_FOUND(404),
        IMAGE_NOT_FOUND(404),
        SOURCE_NOT_FOUND(404),
        ACCOUNT_MISMATCH(409),
        ANNOTATION_CONFLICT(409),
        CARD_CHANGED(409),
        IMAGE_AMBIGUOUS(409),
        MUTATION_ID_REUSED(409),
        SYNC_SUPERSEDED(409),
        REPOSITORY_RENAMED(409),
        ANNOTATION_DELETED(410),
        DOCUMENT_DELETED(410),
        REQUEST_TOO_LARGE(413),
        DOCUMENT_LIMIT(422),
        INVALID_ANCHOR(422),
        LIBRARY_TOO_LARGE(422),
        UNSUPPORTED_CONTENT(422),
        GITHUB_INVALID_RESPONSE(502),
        GITHUB_RESPONSE_LIMIT(502),
        GITHUB_TREE_INCOMPLETE(502),
        GITHUB_INSTALLATION_LIMIT(502),
        GITHUB_REPOSITORY_LIMIT(502),
        GITHUB_UNAVAILABLE(503),
        SIGN_IN_BUSY(503);

        public final int status;

        Code(int status) {
            this.status = status;
        }
    }

    public final Code code;

    public ApiFailure(Code code, String message) {
        super(message);
        this.code = code;
    }

    /** Absent and not this user's are one answer, so ids reveal nothing about other users' data. */
    public static ApiFailure notFound() {
        return new ApiFailure(Code.NOT_FOUND, "Not found.");
    }
}
