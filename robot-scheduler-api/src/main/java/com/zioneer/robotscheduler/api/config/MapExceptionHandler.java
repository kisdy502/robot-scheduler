package com.zioneer.robotscheduler.api.config;

import com.zioneer.robotscheduler.api.controller.MapController;
import com.zioneer.robotscheduler.common.core.error.ErrorCode;
import com.zioneer.robotscheduler.common.core.result.Result;
import com.zioneer.robotscheduler.core.map.MapActivationConflictException;
import com.zioneer.robotscheduler.core.map.MapMetadataException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 地图管理错误使用 Result；不改变调度兼容接口的 FastAPI detail 信封。 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = MapController.class)
public class MapExceptionHandler {
    @ExceptionHandler(MapActivationConflictException.class)
    public ResponseEntity<Result<Void>> conflict(MapActivationConflictException error) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Result.fail(error));
    }

    @ExceptionHandler(MapMetadataException.class)
    public ResponseEntity<Result<Void>> invalidMetadata(MapMetadataException error) {
        return ResponseEntity.badRequest().body(Result.fail(ErrorCode.BAD_REQUEST, error.getMessage()));
    }
}
