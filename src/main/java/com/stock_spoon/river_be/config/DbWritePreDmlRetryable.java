package com.stock_spoon.river_be.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Retries a write handler only before any mutation attempt in the HTTP request.
 * Services must use the tracked DataSource and must not perform irreversible external effects
 * or mutating SELECT functions before that boundary. This annotation never queues HTTP commands.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface DbWritePreDmlRetryable {
}
