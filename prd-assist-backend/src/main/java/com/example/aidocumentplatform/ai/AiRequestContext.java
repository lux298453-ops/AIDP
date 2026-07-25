package com.example.aidocumentplatform.ai;

public final class AiRequestContext {

    private static final ThreadLocal<Long> USER_ID = new ThreadLocal<>();

    private AiRequestContext() {
    }

    public static void setUserId(Long userId) {
        USER_ID.set(userId);
    }

    public static Long getUserId() {
        return USER_ID.get();
    }

    public static void clear() {
        USER_ID.remove();
    }
}
