package com.example.aidocumentplatform.exception;

import com.example.aidocumentplatform.model.dto.response.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

import java.util.stream.Collectors;

/**
 * 全局异常处理器 —— 统一拦截各类异常，返回 Result 格式的错误响应。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 业务异常处理。
     * 如：用户名已存在、用户名或密码错误等，返回 400。
     */
    @ExceptionHandler(BusinessException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleBusinessException(BusinessException e) {
        log.warn("业务异常: code={}, message={}", e.getCode(), e.getMessage());
        return Result.fail(e.getCode(), e.getMessage());
    }

    /**
     * 参数校验异常处理（@Valid 校验失败时触发）。
     * 收集所有字段的错误信息，合并为一条 message 返回。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleValidationException(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        log.warn("参数校验失败: {}", message);
        return Result.fail(400, message);
    }

    /**
     * 非法参数（如自定义模板未上传、文件类型错误）。
     */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("非法参数: {}", e.getMessage());
        return Result.fail(400, e.getMessage());
    }

    /**
     * 客户端断开（SSE/长连接）：响应通道已不可写，不能再返回 JSON Result，
     * 否则会二次抛出 "No converter for Result with Content-Type text/event-stream"。
     */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleAsyncRequestNotUsable(AsyncRequestNotUsableException e) {
        log.debug("客户端已断开连接，忽略: {}", e.toString());
    }

    /**
     * 兜底异常处理 —— 未预期的运行时异常统一返回 500。
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleException(Exception e) {
        // SSE/长连接客户端断开：不要写 JSON Result
        if (isClientDisconnect(e)) {
            log.debug("客户端已断开连接（兜底），忽略: {}", e.toString());
            return null;
        }
        log.error("服务器内部错误", e);
        return Result.fail(500, "服务器内部错误");
    }

    private static boolean isClientDisconnect(Throwable e) {
        Throwable cursor = e;
        while (cursor != null) {
            if (cursor instanceof AsyncRequestNotUsableException) {
                return true;
            }
            String msg = cursor.getMessage() == null ? "" : cursor.getMessage().toLowerCase();
            String name = cursor.getClass().getName().toLowerCase();
            if (msg.contains("broken pipe")
                    || msg.contains("connection reset")
                    || msg.contains("connection aborted")
                    || msg.contains("async request not usable")
                    || name.contains("clientabortexception")) {
                return true;
            }
            cursor = cursor.getCause();
        }
        return false;
    }
}
