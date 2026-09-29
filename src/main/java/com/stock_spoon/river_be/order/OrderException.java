package com.stock_spoon.river_be.order;

import org.springframework.http.HttpStatus;

public class OrderException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public OrderException(String message) {
        this(HttpStatus.BAD_REQUEST, "INVALID_ORDER", message);
    }

    public OrderException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }
}
