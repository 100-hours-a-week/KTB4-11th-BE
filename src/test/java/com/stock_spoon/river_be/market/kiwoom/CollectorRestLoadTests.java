package com.stock_spoon.river_be.market.kiwoom;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.core.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 실제 키움 부하 없이 운영 HTTP 전달·TR 제한 코드를 측정한다. Redis 읽기는 일정한 담당 값으로 대체한다. */
@EnabledIfEnvironmentVariable(named = "REST_LOAD_TEST", matches = "true")
class CollectorRestLoadTests {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String SECRET = "test-only-load-secret";

    @Test
    void measuresWaitingAndCallerTimeoutsWithExistingSettings() throws Exception {
        var reports = new ArrayList<Report>();
        for (int[] scenario : List.of(new int[]{5, 1, 100}, new int[]{20, 1, 100},
                new int[]{20, 2, 100}, new int[]{10, 1, 1000}, new int[]{30, 1, 100},
                new int[]{50, 1, 100}, new int[]{100, 1, 100}, new int[]{30, 1, 1000})) {
            reports.add(measure(scenario[0], scenario[1], scenario[2]));
        }
        var target = Path.of("build/reports/redis-rest-load.json");
        Files.createDirectories(target.getParent());
        Files.writeString(target, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(reports));
        reports.forEach(report -> System.out.println("REST LOAD: " + JSON.writeValueAsString(report)));
        assertThat(reports.getFirst().success()).isEqualTo(5);
        assertThat(reports.get(2).success()).isEqualTo(20);
        assertThat(reports.get(3).failed()).isZero();
        assertThat(reports.get(6).rejectedBeforeUpstream()).as("3초 초과 대기는 외부 호출 전에 거절됨").isPositive();
        assertThat(reports.get(6).upstreamCompletedFinal()).isLessThan(100);
        assertThat(reports.get(7).rejectedBeforeUpstream()).isPositive();
    }

    private Report measure(int count, int trCount, int delayMs) throws Exception {
        // Java 21 동기화 대기의 가상 스레드 간섭을 피하고 기본 Tomcat처럼 플랫폼 스레드로 측정한다.
        var workers = Executors.newFixedThreadPool(Math.max(64, count * 2 + 8));
        var upstream = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        var internal = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        var completed = new AtomicInteger();
        var rejected = new AtomicInteger();
        var drained = new CountDownLatch(count);
        var arrivals = new ConcurrentHashMap<String, List<Long>>();
        try {
            upstream.setExecutor(workers);
            upstream.createContext("/api/dostk/", exchange -> {
                String api = exchange.getRequestHeaders().getFirst("api-id");
                arrivals.computeIfAbsent(api, ignored -> Collections.synchronizedList(new ArrayList<>())).add(System.nanoTime());
                try {
                    exchange.getRequestBody().readAllBytes();
                    Thread.sleep(delayMs);
                    var bytes = "{\"return_code\":0}".getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    completed.incrementAndGet();
                } catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
                finally { exchange.close(); }
            });
            upstream.start();
            var factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(Duration.ofSeconds(3)); factory.setReadTimeout(Duration.ofSeconds(5));
            var fakeTokens = mock(KiwoomTokenProvider.class);
            when(fakeTokens.accessToken()).thenReturn("dummy-token");
            var direct = new DirectKiwoomRestTransport(RestClient.builder()
                    .baseUrl("http://localhost:" + upstream.getAddress().getPort()).requestFactory(factory).build(), fakeTokens, () -> true);
            var heartbeat = mock(RedisCollectorHeartbeat.class);
            when(heartbeat.ownsLease()).thenReturn(true); when(heartbeat.ownerId()).thenReturn("load-owner");
            var controller = new CollectorQueryController(direct, heartbeat, null);
            internal.setExecutor(workers);
            internal.createContext("/internal/kiwoom/query", exchange -> {
                try {
                    if (!SECRET.equals(exchange.getRequestHeaders().getFirst("X-Kiwoom-Collector-Key"))) {
                        exchange.sendResponseHeaders(401, -1); return;
                    }
                    var query = JSON.readValue(exchange.getRequestBody().readAllBytes(), CollectorQuery.class);
                    var response = controller.query(query);
                    var bytes = JSON.writeValueAsBytes(response);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                } catch (org.springframework.web.server.ResponseStatusException failure) {
                    rejected.incrementAndGet();
                    exchange.sendResponseHeaders(failure.getStatusCode().value(), -1);
                } catch (java.io.IOException failure) {
                    // 호출자가 시간 초과돼도 담당의 처리는 계속되므로 완료까지 측정한다.
                } finally { exchange.close(); drained.countDown(); }
            });
            internal.start();
            var redis = mock(StringRedisTemplate.class);
            ValueOperations<String, String> values = mock(ValueOperations.class);
            when(redis.opsForValue()).thenReturn(values);
            when(values.get("coord:kiwoom:collector")).thenReturn("load-owner");
            var remote = new KiwoomConfig().kiwoomRestTransport(fakeTokens, null, redis, false,
                    "http://localhost:" + internal.getAddress().getPort(), SECRET);
            var ready = new CountDownLatch(count);
            var go = new CountDownLatch(1);
            var outcomes = new ArrayList<Future<Outcome>>();
            long started = System.nanoTime();
            for (int i = 0; i < count; i++) {
                String api = i % trCount == 0 ? "ka10100" : "ka10081";
                outcomes.add(workers.submit(() -> {
                    ready.countDown(); go.await();
                    long sent = System.nanoTime();
                    boolean success;
                    try { success = "0".equals(String.valueOf(remote.query(api, "ka10081".equals(api) ? "/api/dostk/chart" : "/api/dostk/stkinfo",
                            "ka10081".equals(api) ? Map.of("stk_cd", "005930", "base_dt", "20261016", "upd_stkpc_tp", "0") : Map.of("stk_cd", "005930")).get("return_code"))); }
                    catch (IllegalStateException failure) { success = false; }
                    return new Outcome(success, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - sent));
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue(); go.countDown();
            var results = new ArrayList<Outcome>();
            for (var outcome : outcomes) results.add(outcome.get(15, TimeUnit.SECONDS));
            long clientElapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            int completedAtReturn = completed.get();
            assertThat(drained.await(30, TimeUnit.SECONDS)).as("담당의 시작된 요청 정리 완료").isTrue();
            assertThat(completed.get() + rejected.get()).isEqualTo(count);
            long minimumSpacing = Long.MAX_VALUE;
            for (var times : arrivals.values()) {
                times.sort(Long::compareTo);
                for (int i = 1; i < times.size(); i++) {
                    long spacing = TimeUnit.NANOSECONDS.toMillis(times.get(i) - times.get(i - 1));
                    minimumSpacing = Math.min(minimumSpacing, spacing);
                }
            }
            long[] durations = results.stream().mapToLong(Outcome::elapsedMs).sorted().toArray();
            int successes = (int) results.stream().filter(Outcome::success).count();
            return new Report(count, trCount, delayMs, successes, count - successes,
                    durations[(count - 1) / 2], durations[(int) Math.ceil(count * .95) - 1], durations[count - 1],
                    clientElapsed, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), completedAtReturn,
                    completed.get(), rejected.get(), minimumSpacing);
        } finally { internal.stop(0); upstream.stop(0); workers.shutdownNow(); }
    }
    record Outcome(boolean success, long elapsedMs) {}
    record Report(int concurrentRequests, int trCount, int upstreamDelayMs, int success, int failed,
                  long p50Ms, long p95Ms, long maxMs, long callerBatchMs, long collectorDrainMs,
                  int upstreamCompletedAtCallerReturn, int upstreamCompletedFinal, int rejectedBeforeUpstream,
                  long minimumSameTrSpacingMs) {}
}
