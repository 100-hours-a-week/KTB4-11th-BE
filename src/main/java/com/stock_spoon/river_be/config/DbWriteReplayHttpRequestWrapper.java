package com.stock_spoon.river_be.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.util.ContentCachingRequestWrapper;

/** Caches a bounded body for explicitly marked business-command fallback requests. */
final class DbWriteReplayHttpRequestWrapper extends ContentCachingRequestWrapper {
    static final int MAX_CACHED_BODY_BYTES = 240_000;

    private boolean contentOverflowed;

    DbWriteReplayHttpRequestWrapper(HttpServletRequest request) {
        super(request, MAX_CACHED_BODY_BYTES);
    }

    @Override
    protected void handleContentOverflow(int contentCacheLimit) {
        contentOverflowed = true;
    }

    boolean contentOverflowed() {
        return contentOverflowed;
    }
}
