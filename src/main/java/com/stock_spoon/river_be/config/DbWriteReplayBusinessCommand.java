package com.stock_spoon.river_be.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Marks a write endpoint whose request can be recorded as a business-command fallback. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface DbWriteReplayBusinessCommand {
}
