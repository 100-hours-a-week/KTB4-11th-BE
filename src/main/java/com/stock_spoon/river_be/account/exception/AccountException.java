package com.stock_spoon.river_be.account.exception;

import org.springframework.http.HttpStatus;

public class AccountException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public AccountException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
