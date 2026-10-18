package com.stock_spoon.river_be.dbwrite;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class DbWriteReplayExceptionHandler {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(DbWriteReplayExceptionHandler.class);

    @ExceptionHandler(DbWriteReplayEnqueueException.class)
    ResponseEntity<Map<String, String>> handle(DbWriteReplayEnqueueException error) {
        log.error("event=db_write_replay_enqueue_failed causeType={}",
                error.getCause() == null ? "unknown" : error.getCause().getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("code", "DB_WRITE_REPLAY_UNAVAILABLE",
                        "message", "쓰기 요청을 임시 보관하지 못했습니다. 잠시 후 다시 시도하세요."));
    }
}
