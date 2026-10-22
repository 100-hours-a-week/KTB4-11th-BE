package com.stock_spoon.river_be.market.kiwoom;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DirectRateLimitTests {
    @Test
    void requestsWaitingThreeSecondsFailWithoutCallingUpstream() throws Exception {
        var builder = RestClient.builder().baseUrl("https://test.invalid");
        var server = org.springframework.test.web.client.MockRestServiceServer.bindTo(builder).build();
        var sent = new java.util.concurrent.atomic.AtomicInteger();
        server.expect(org.springframework.test.web.client.ExpectedCount.manyTimes(),
                        org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo("https://test.invalid/query"))
                .andExpect(request -> sent.incrementAndGet())
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(
                        "{\"return_code\":0}", org.springframework.http.MediaType.APPLICATION_JSON));
        var tokens = mock(KiwoomTokenProvider.class);
        when(tokens.accessToken()).thenReturn("dummy");
        var direct = new DirectKiwoomRestTransport(builder.build(), tokens, () -> true);
        try (var workers = Executors.newFixedThreadPool(30)) {
            var ready = new CountDownLatch(30);
            var go = new CountDownLatch(1);
            var calls = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < 30; i++) calls.add(workers.submit(() -> {
                ready.countDown(); go.await();
                long started = System.nanoTime();
                try { direct.query("ka10001", "/query", Map.of()); return true; }
                catch (IllegalStateException failure) {
                    assertThat(failure).hasMessage("키움 조회 대기 시간이 초과되었습니다.");
                    assertThat(System.nanoTime() - started).isGreaterThanOrEqualTo(TimeUnit.SECONDS.toNanos(3));
                    return false;
                }
            }));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            long started = System.nanoTime(); go.countDown();
            int success = 0;
            for (var call : calls) if (call.get(7, TimeUnit.SECONDS)) success++;
            assertThat(success).isLessThan(30);
            assertThat(sent.get()).isEqualTo(success);
            assertThat(System.nanoTime() - started).isLessThan(TimeUnit.MILLISECONDS.toNanos(4500));
            server.verify();
        }
    }

    @Test
    void everyRollingSecondHasAtMostFivePermitsAcrossMultipleWindows() throws Exception {
        var direct = new DirectKiwoomRestTransport(RestClient.create(), mock(KiwoomTokenProvider.class), () -> true);
        try (var workers = Executors.newFixedThreadPool(15)) {
            var go = new CountDownLatch(1);
            var calls = new ArrayList<Future<Long>>();
            for (int i = 0; i < 15; i++) calls.add(workers.submit(() -> { go.await(); return direct.acquirePermit("ka10001"); }));
            go.countDown();
            var starts = new ArrayList<Long>();
            for (var call : calls) starts.add(call.get(5, TimeUnit.SECONDS));
            starts.sort(Long::compareTo);
            for (int i = 0; i + 5 < starts.size(); i++) {
                assertThat(starts.get(i + 5) - starts.get(i)).isGreaterThanOrEqualTo(TimeUnit.SECONDS.toNanos(1));
            }
        }
    }

    @Test
    void authenticationRetryConsumesCapacityWithoutRefundingFailedRequest() {
        var builder = RestClient.builder().baseUrl("https://test.invalid");
        var server = org.springframework.test.web.client.MockRestServiceServer.bindTo(builder).build();
        var starts = new ArrayList<Long>();
        server.expect(org.springframework.test.web.client.ExpectedCount.times(4),
                        org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo("https://test.invalid/query"))
                .andExpect(request -> starts.add(System.nanoTime()))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(
                        "{\"return_code\":0}", org.springframework.http.MediaType.APPLICATION_JSON));
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo("https://test.invalid/query"))
                .andExpect(request -> starts.add(System.nanoTime()))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(
                        "{\"return_code\":8005}", org.springframework.http.MediaType.APPLICATION_JSON));
        server.expect(org.springframework.test.web.client.ExpectedCount.once(),
                        org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo("https://test.invalid/query"))
                .andExpect(request -> starts.add(System.nanoTime()))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(
                        "{\"return_code\":0}", org.springframework.http.MediaType.APPLICATION_JSON));
        var tokens = mock(KiwoomTokenProvider.class);
        when(tokens.accessToken()).thenReturn("old", "old", "old", "old", "old", "new");
        var direct = new DirectKiwoomRestTransport(builder.build(), tokens, () -> true);
        long batchStarted = System.nanoTime();
        for (int i = 0; i < 5; i++) direct.query("ka10001", "/query", Map.of());
        assertThat(starts).hasSize(6);
        assertThat(starts.getLast() - batchStarted).isGreaterThanOrEqualTo(TimeUnit.SECONDS.toNanos(1));
        verify(tokens).invalidate("old");
        server.verify();
    }

    @Test
    void firstFiveOverlapSixthWaitsAndOtherTrHasIndependentCapacity() throws Exception {
        var threads = Executors.newFixedThreadPool(24);
        var server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        var firstFive = new CountDownLatch(5);
        var other = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var times = new CopyOnWriteArrayList<Long>();
        server.setExecutor(threads);
        server.createContext("/query", exchange -> {
            exchange.getRequestBody().readAllBytes();
            if ("ka10001".equals(exchange.getRequestHeaders().getFirst("api-id"))) {
                times.add(System.nanoTime()); firstFive.countDown();
            } else other.countDown();
            try { release.await(8, TimeUnit.SECONDS); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
            byte[] body = "{\"return_code\":0}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        var tokens = mock(KiwoomTokenProvider.class); when(tokens.accessToken()).thenReturn("dummy");
        var direct = new DirectKiwoomRestTransport(RestClient.builder().baseUrl("http://localhost:" + server.getAddress().getPort()).build(), tokens, () -> true);
        try {
            var tasks = new ArrayList<Future<?>>();
            long batchStarted = System.nanoTime();
            for (int i = 0; i < 5; i++) tasks.add(threads.submit(() -> direct.query("ka10001", "/query", Map.of())));
            assertThat(firstFive.await(2, TimeUnit.SECONDS)).as("최초 5개는 응답을 기다리며 서로 막지 않음").isTrue();
            tasks.add(threads.submit(() -> direct.query("ka10001", "/query", Map.of())));
            tasks.add(threads.submit(() -> direct.query("ka10100", "/query", Map.of())));
            assertThat(other.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(times).hasSize(5);
            release.countDown();
            for (var task : tasks) task.get(5, TimeUnit.SECONDS);
            var sorted = times.stream().sorted().toList();
            assertThat(sorted).hasSize(6);
            assertThat(sorted.get(5) - batchStarted).isGreaterThanOrEqualTo(TimeUnit.SECONDS.toNanos(1));
        } finally { release.countDown(); server.stop(0); threads.shutdownNow(); }
    }
}
