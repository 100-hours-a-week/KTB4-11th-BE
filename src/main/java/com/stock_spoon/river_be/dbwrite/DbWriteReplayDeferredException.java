package com.stock_spoon.river_be.dbwrite;

public class DbWriteReplayDeferredException extends RuntimeException {
    private final String requestId;

    public DbWriteReplayDeferredException(String requestId, String message) {
        super(message);
        this.requestId = requestId;
    }

    public String requestId() {
        return requestId;
    }
}
