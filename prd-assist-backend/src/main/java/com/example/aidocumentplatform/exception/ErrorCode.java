package com.example.aidocumentplatform.exception;

import lombok.Getter;

@Getter
public enum ErrorCode {

    // 通用错误
    INTERNAL_ERROR(500, "服务器内部错误"),
    ACCESS_DENIED(403, "无权访问"),

    // 用户相关
    USERNAME_EXISTS(4001, "用户名已存在"),
    INVALID_CREDENTIALS(4002, "用户名或密码错误"),

    // 文档相关
    DOCUMENT_NOT_FOUND(4101, "文档不存在"),
    FILE_UPLOAD_FAILED(4102, "文件上传失败"),

    // 任务相关
    TASK_NOT_FOUND(4201, "任务不存在");

    private final int code;
    private final String defaultMessage;

    ErrorCode(int code, String defaultMessage) {
        this.code = code;
        this.defaultMessage = defaultMessage;
    }
}
