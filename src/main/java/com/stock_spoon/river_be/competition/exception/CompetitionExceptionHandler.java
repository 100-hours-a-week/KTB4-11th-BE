package com.stock_spoon.river_be.competition.exception;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice // 컨트롤러마다 try-catch를 작성하지 않고 예외 응답을 한곳에서 만든다.
public class CompetitionExceptionHandler {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CompetitionExceptionHandler.class);
    @ExceptionHandler(CompetitionException.class)
    public ResponseEntity<Map<String, String>> handle(CompetitionException error) {
        var level = error.getStatus().is5xxServerError() ? log.atError() : log.atWarn();
        level.log("event=api_rejected stage=competition_exception_handler status={} code={}", error.getStatus().value(), error.getCode());
        return ResponseEntity.status(error.getStatus())
                .body(Map.of("code", error.getCode(), "message", error.getMessage()));
    }
}
