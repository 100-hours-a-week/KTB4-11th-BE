package com.stock_spoon.river_be.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Captures a typed write command for SQS; it never retries or consumes the request. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface DbWriteReplayBusinessCommand {
    Type value();

    enum Type {
        ACCOUNT_CREATE, ACCOUNT_RENAME, WATCHLIST_ADD, WATCHLIST_REMOVE, ORDER_CREATE, ORDER_CANCEL, COMPETITION_CREATE
    }
}
