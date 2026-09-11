package com.example.aidocumentplatform.ai;

public final class AiRequestContext {

    private static final ThreadLocal<Long> USER_ID = new ThreadLocal<>();
    private static final ThreadLocal<RequestOptions> REQUEST_OPTIONS = new ThreadLocal<>();

    private AiRequestContext() {
    }

    public static void setUserId(Long userId) {
        USER_ID.set(userId);
    }

    public static Long getUserId() {
        return USER_ID.get();
    }

    public static void setRequestOptions(String reasoningEffort, Integer maxOutputTokens,
                                         Integer timeoutSeconds, Integer retryCount,
                                         boolean fallbackEnabled) {
        REQUEST_OPTIONS.set(new RequestOptions(
                reasoningEffort,
                maxOutputTokens,
                timeoutSeconds,
                retryCount,
                fallbackEnabled));
    }

    public static RequestOptions getRequestOptions() {
        return REQUEST_OPTIONS.get();
    }

    public static void clear() {
        USER_ID.remove();
        REQUEST_OPTIONS.remove();
    }

    public record RequestOptions(
            String reasoningEffort,
            Integer maxOutputTokens,
            Integer timeoutSeconds,
            Integer retryCount,
            boolean fallbackEnabled
    ) {
    }
}
