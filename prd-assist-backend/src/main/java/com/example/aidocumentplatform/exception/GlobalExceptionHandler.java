package com.example.aidocumentplatform.exception;

import com.example.aidocumentplatform.model.dto.response.Result;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleBusinessException(BusinessException e) {
        log.warn("Business exception: code={}, message={}", e.getCode(), e.getMessage());
        return Result.fail(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleValidationException(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        log.warn("Validation failed: {}", message);
        return Result.fail(400, message.isBlank() ? "请求参数校验失败" : message);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleConstraintViolation(ConstraintViolationException e) {
        String message = e.getConstraintViolations().stream()
                .map(violation -> violation.getMessage())
                .collect(Collectors.joining("; "));
        log.warn("Constraint violation: {}", message);
        return Result.fail(400, message.isBlank() ? "请求参数不合法" : message);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleNotReadable(HttpMessageNotReadableException e) {
        log.warn("Request body parse failed: {}", e.getMessage());
        return Result.fail(400, "请求体格式错误，请检查 JSON 内容和枚举值");
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleMissingParameter(MissingServletRequestParameterException e) {
        String message = "缺少必填参数: " + e.getParameterName();
        log.warn(message);
        return Result.fail(400, message);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        String message = "参数类型错误: " + e.getName();
        log.warn("{}", message, e);
        return Result.fail(400, message);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleMaxUpload(MaxUploadSizeExceededException e) {
        log.warn("Upload too large: {}", e.getMessage());
        return Result.fail(400, "上传文件过大，请压缩后重试");
    }

    @ExceptionHandler(AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public Result<Void> handleAccessDenied(AccessDeniedException e) {
        log.warn("Access denied: {}", e.getMessage());
        return Result.fail(403, "无权限访问该资源");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("Illegal argument: {}", e.getMessage());
        return Result.fail(400, e.getMessage());
    }

    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleAsyncRequestNotUsable(AsyncRequestNotUsableException e) {
        log.debug("Client disconnected, ignoring async response write: {}", e.toString());
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleException(Exception e) {
        if (isClientDisconnect(e)) {
            log.debug("Client disconnected, ignoring terminal exception: {}", e.toString());
            return null;
        }
        log.error("Unexpected server error", e);
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