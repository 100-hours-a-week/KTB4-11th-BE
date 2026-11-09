package com.stock_spoon.river_be.config;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import static org.mockito.Mockito.*;

class ProfileWriteReplayExclusionTests {
    @Test void profileWritesDoNotCaptureDatabaseReplayCommands() throws Exception {
        var context = mock(DbWriteReplayRequestContext.class);
        var filter = new DbWriteReplayRequestFilter(context);
        for (String[] route : new String[][] {
                {"PATCH", "/api/v1/users/me"},
                {"POST", "/api/v1/users/me/profile-image/presigned-url"}}) {
            var request = new MockHttpServletRequest(route[0], route[1]);
            filter.doFilter(request, new MockHttpServletResponse(), mock(FilterChain.class));
        }
        verifyNoInteractions(context);
    }
}
