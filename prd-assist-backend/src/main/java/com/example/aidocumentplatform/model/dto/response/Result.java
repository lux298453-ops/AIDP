package com.example.aidocumentplatform.model.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 统一响应体。
 * 所有 API 接口统一使用此类包装返回结果，前端根据 code 判断业务是否成功。
 *
 * @param <T> 响应数据的类型
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Result<T> {

    /** 业务状态码，200 表示成功 */
    private int code;

    /** 提示信息 */
    private String message;

    /** 响应数据，成功时返回 */
    private T data;

    // ========== 成功快捷方法 ==========

    /** 成功 + 返回数据 */
    public static <T> Result<T> ok(T data) {
        return Result.<T>builder()
                .code(200)
                .message("success")
                .data(data)
                .build();
    }

    /** 成功 + 无数据（如注册成功无需返回内容） */
    public static <T> Result<T> ok() {
        return Result.<T>builder()
                .code(200)
                .message("success")
                .build();
    }

    // ========== 失败快捷方法 ==========

    /** 失败 + 自定义 code 和 message */
    public static <T> Result<T> fail(int code, String message) {
        return Result.<T>builder()
                .code(code)
                .message(message)
                .build();
    }
}
