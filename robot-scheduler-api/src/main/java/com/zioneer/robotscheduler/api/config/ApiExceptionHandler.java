package com.zioneer.robotscheduler.api.config;

import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.zioneer.robotscheduler.core.dispatch.SchedulerHttpException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * 对外调度端点全局异常处理：模拟旧 FastAPI 错误契约（HTTP 状态码 + {"detail": "..."}）。
 *
 * <p>存在原因：上层 taskflow 可能依赖该错误格式做分支处理；
 * 内部管理接口（如有）另行使用 Result 信封，不在此处处理。</p>
 */
@Slf4j
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(SchedulerHttpException.class)
    public ResponseEntity<Map<String, Object>> handleSchedulerHttp(SchedulerHttpException e) {
        return detail(HttpStatus.valueOf(e.getHttpStatus()), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return detail(HttpStatus.BAD_REQUEST, message);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> handleMissingParam(MissingServletRequestParameterException e) {
        return detail(HttpStatus.BAD_REQUEST, e.getParameterName() + " is required");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return detail(HttpStatus.BAD_REQUEST,
                "invalid value for parameter '" + e.getName() + "': " + e.getValue());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadable(HttpMessageNotReadableException e) {
        if (e.getCause() instanceof InvalidFormatException formatException) {
            return detail(HttpStatus.BAD_REQUEST, "invalid request body: " + formatException.getValue());
        }
        return detail(HttpStatus.BAD_REQUEST, "invalid request body");
    }

    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNoResource(
            org.springframework.web.servlet.resource.NoResourceFoundException e) {
        // 静态资源 404（favicon/静态文件等）：返回 404，不打堆栈（避免观测日志噪音）
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("detail", "resource not found: " + e.getResourcePath()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception e) {
        // Spring 请求异常自带状态和协议头（如 405 的 Allow），不能被兜底改写为 500。
        if (e instanceof ErrorResponse error && error.getStatusCode().is4xxClientError()) {
            String message = error.getBody().getDetail();
            return ResponseEntity.status(error.getStatusCode()).headers(error.getHeaders())
                    .body(Map.of("detail", message == null ? "invalid request" : message));
        }
        log.error("unexpected scheduler api error", e);
        if (e instanceof ErrorResponse error) {
            return ResponseEntity.status(error.getStatusCode()).headers(error.getHeaders())
                    .body(Map.of("detail", "internal scheduler error"));
        }
        return detail(HttpStatus.INTERNAL_SERVER_ERROR, "internal scheduler error");
    }

    private ResponseEntity<Map<String, Object>> detail(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("detail", message == null ? "" : message));
    }
}
