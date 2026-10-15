package com.stock_spoon.river_be.competition.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

// RuntimeException을 사용하므로 생성 도중 발생하면 기본 트랜잭션 정책에 따라 롤백된다.
@Getter
public class CompetitionException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public CompetitionException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }
}
