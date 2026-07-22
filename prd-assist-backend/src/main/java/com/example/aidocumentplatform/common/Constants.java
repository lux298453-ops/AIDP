package com.example.aidocumentplatform.common;

public class Constants {

    private Constants() {}

    public static final String TOKEN_PREFIX = "Bearer ";
    public static final String AUTH_HEADER = "Authorization";

    /** 文件上传最大大小: 50MB */
    public static final long MAX_UPLOAD_SIZE = 50 * 1024 * 1024;

    /** SSE 连接超时（毫秒） */
    public static final long SSE_TIMEOUT_MS = 30 * 60 * 1000L;
}
