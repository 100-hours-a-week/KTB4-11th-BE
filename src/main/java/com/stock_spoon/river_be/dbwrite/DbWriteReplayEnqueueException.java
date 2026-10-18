package com.stock_spoon.river_be.dbwrite;

public class DbWriteReplayEnqueueException extends RuntimeException {
    public DbWriteReplayEnqueueException(Throwable cause) {
        super("DB write replay request could not be stored in SQS", cause);
    }
}
