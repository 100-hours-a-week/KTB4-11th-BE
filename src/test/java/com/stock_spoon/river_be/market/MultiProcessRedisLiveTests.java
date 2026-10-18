package com.stock_spoon.river_be.market;

import java.io.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import static org.assertj.core.api.Assertions.*;

/** 실제 JVM 세 개와 공유 H2 TCP DB를 사용한다. 외부 키움만 테스트 응답으로 대체한다. */
@EnabledIfEnvironmentVariable(named = "REDIS_LIVE_TEST", matches = "true")
class MultiProcessRedisLiveTests {
    @Test
    @EnabledIfEnvironmentVariable(named = "REDIS_LOAD_TEST", matches = "true")
    void burstQuotesExecuteOneHundredOrdersOnceAcrossThreeProcesses() throws Exception {
        var connection = new LettuceConnectionFactory("localhost", 6380);
        connection.afterPropertiesSet(); connection.start();
        var redis = new StringRedisTemplate(connection);
        Object database = null;
        boolean keysOwned = false;
        var keys = List.of("quote:TST009:metadata", "price:TST009");
        try {
            assertThat(redis.hasKey("coord:kiwoom:collector")).as("기존 담당 보호").isFalse();
            for (String key : keys) assertThat(redis.hasKey(key)).as("기존 테스트 키 보호: %s", key).isFalse();
            keysOwned = true;
            var h2 = Class.forName("org.h2.tools.Server");
            database = h2.getMethod("createTcpServer", String[].class).invoke(null,
                    (Object) new String[]{"-tcpPort", "0", "-ifNotExists"});
            h2.getMethod("start").invoke(database);
            int port = (int) h2.getMethod("getPort").invoke(database);
            String url = "jdbc:h2:tcp://localhost:" + port + "/mem:load-" + UUID.randomUUID()
                    + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE";
            try (var collector = new Node(true, url, "")) {
                try (var first = new Node(false, url, collector.ask("endpoint"));
                     var second = new Node(false, url, collector.ask("endpoint"))) {
                    assertThat(first.ask("seed-load")).isEqualTo("ok");
                    assertThat(first.ask("subscribe")).isEqualTo("ok");
                    assertThat(second.ask("subscribe")).isEqualTo("ok");
                    long highStarted = System.nanoTime();
                    assertThat(collector.ask("emit-load-high")).isEqualTo("ok");
                    assertThat(first.ask("load-high-received", 75)).isEqualTo("20");
                    assertThat(second.ask("load-high-received", 75)).isEqualTo("20");
                    assertThat(first.ask("state")).isEqualTo("101:0:20000000");
                    long highMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - highStarted);
                    long lowStarted = System.nanoTime();
                    assertThat(collector.ask("emit-load-low")).isEqualTo("ok");
                    assertThat(first.ask("load-low-received", 75)).isEqualTo("40");
                    assertThat(second.ask("load-low-received", 75)).isEqualTo("40");
                    assertThat(first.ask("load-assets", 75)).isEqualTo("100:100:100:8050000");
                    assertThat(second.ask("load-assets", 75)).isEqualTo("100:100:100:8050000");
                    assertThat(second.ask("state")).isEqualTo("1:100:8050000");
                    assertThat(collector.ask("owner")).isEqualTo("true");
                    var report = Path.of("build/reports/redis-order-load.json");
                    java.nio.file.Files.createDirectories(report.getParent());
                    java.nio.file.Files.writeString(report, tools.jackson.databind.json.JsonMapper.builder().build()
                            .writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                                    "beProcesses", 3, "normalBeProcesses", 2, "eligibleOrders", 100,
                                    "sentPriceEvents", 40, "receivedPerNormalBe", 40,
                                    "highPricePhaseMs", highMs,
                                    "executionPhaseMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - lowStarted),
                                    "executions", 100, "cash", 8050000, "holdingQuantity", 100)));
                }
            }
        } finally {
            if (database != null) database.getClass().getMethod("stop").invoke(database);
            if (keysOwned) {
                try { awaitCollectorStopped(redis); }
                finally { for (String key : keys) redis.delete(key); }
            }
            connection.destroy();
        }
    }

    @Test
    void redisReconnectResumesOriginalQuotesWithoutRestartingRestCollector() throws Exception {
        var connection = new LettuceConnectionFactory("localhost", 6380);
        connection.afterPropertiesSet(); connection.start();
        var redis = new StringRedisTemplate(connection);
        Object database = null;
        boolean keysOwned = false;
        var keys = List.of("quote:TST009:metadata", "price:TST009");
        try (var proxy = new RedisFaultProxy()) {
            assertThat(redis.hasKey("coord:kiwoom:collector")).isFalse();
            for (String key : keys) assertThat(redis.hasKey(key)).as("기존 테스트 키 보호: %s", key).isFalse();
            keysOwned = true;
            var h2 = Class.forName("org.h2.tools.Server");
            database = h2.getMethod("createTcpServer", String[].class).invoke(null,
                    (Object) new String[]{"-tcpPort", "0", "-ifNotExists"});
            h2.getMethod("start").invoke(database);
            int dbPort = (int) h2.getMethod("getPort").invoke(database);
            String url = "jdbc:h2:tcp://localhost:" + dbPort + "/mem:fault-" + UUID.randomUUID()
                    + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE";
            try (var collector = new Node(true, url, "", proxy.port(), 0)) {
                String endpoint = collector.ask("endpoint");
                try (var first = new Node(false, url, endpoint, proxy.port(), 0);
                     var second = new Node(false, url, endpoint, proxy.port(), 0)) {
                    assertThat(first.ask("query")).isEqualTo("0");
                    assertThat(first.ask("seed")).isEqualTo("ok");
                    assertThat(first.ask("subscribe")).isEqualTo("ok");
                    assertThat(second.ask("subscribe")).isEqualTo("ok");
                    assertThat(collector.ask("emit-high")).isEqualTo("ok");
                    assertThat(first.ask("event")).isEqualTo("200000");
                    assertThat(second.ask("event")).isEqualTo("200000");
                    proxy.disconnect();
                    assertThat(first.ask("query")).isEqualTo("unavailable");
                    assertThat(first.ask("state")).isEqualTo("1:0:1000000");
                    awaitCollectorStopped(redis);
                    proxy.reconnect();
                    assertThat(collector.ask("owner")).isEqualTo("false");
                    // 재연결 ACK는 스냅샷만으로 주문을 체결하지 않는다.
                    assertThat(first.ask("subscribe")).isEqualTo("ok");
                    assertThat(second.ask("subscribe")).isEqualTo("ok");
                    assertThat(first.ask("state")).isEqualTo("1:0:1000000");
                    assertThat(first.ask("clear-events")).isEqualTo("ok");
                    assertThat(second.ask("clear-events")).isEqualTo("ok");
                    org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(30)).until(() ->
                            "true".equals(first.ask("subscription-ready")) && "true".equals(second.ask("subscription-ready")));
                    new MultiProcessRedisNode.Fixture().emit(redis, 119500);
                    assertThat(first.ask("event")).isEqualTo("119500");
                    assertThat(second.ask("event")).isEqualTo("119500");
                    assertThat(first.ask("assets")).isEqualTo("1:880500");
                    assertThat(second.ask("assets")).isEqualTo("1:880500");
                }
            }
        } finally {
            if (database != null) database.getClass().getMethod("stop").invoke(database);
            if (keysOwned) {
                try { awaitCollectorStopped(redis); }
                finally { for (String key : keys) redis.delete(key); }
            }
            connection.destroy();
        }
    }

    @Test
    void threeProcessesShareRestMetadataAndReceiveDirectQuotesIndependentlyOfCollector() throws Exception {
        var connection = new LettuceConnectionFactory("localhost", 6380);
        connection.afterPropertiesSet(); connection.start();
        var redis = new StringRedisTemplate(connection);
        Object database = null;
        boolean testKeysOwned = false;
        try {
            assertThat(redis.hasKey("coord:kiwoom:collector")).as("기존 담당 실행 중이면 중단").isFalse();
            assertThat(redis.hasKey("quote:TST009:metadata")).isFalse();
            assertThat(redis.hasKey("price:TST009")).isFalse();
            testKeysOwned = true;
            var h2 = Class.forName("org.h2.tools.Server");
            database = h2.getMethod("createTcpServer", String[].class).invoke(null,
                    (Object) new String[]{"-tcpPort", "0", "-ifNotExists"});
            h2.getMethod("start").invoke(database);
            int dbPort = (int) h2.getMethod("getPort").invoke(database);
            String url = "jdbc:h2:tcp://localhost:" + dbPort + "/mem:multi-" + UUID.randomUUID()
                    + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE";
            try (var collector = new Node(true, url, "")) {
                String endpoint = collector.ask("endpoint");
                try (var first = new Node(false, url, endpoint); var second = new Node(false, url, endpoint)) {
                    assertThat(first.ask("metadata")).isEqualTo("TEST STOCK");
                    assertThat(second.ask("metadata")).isEqualTo("TEST STOCK");
                    assertThat(collector.ask("calls")).isEqualTo("1");
                    assertThat(first.ask("query")).isEqualTo("0");
                    assertThat(second.ask("query")).isEqualTo("0");
                    assertThat(first.ask("seed")).isEqualTo("ok");
                    assertThat(first.ask("subscribe")).isEqualTo("ok");
                    assertThat(second.ask("subscribe")).isEqualTo("ok");
                    assertThat(collector.ask("emit")).isEqualTo("ok");
                    assertThat(first.ask("event")).isEqualTo("119500");
                    assertThat(second.ask("event")).isEqualTo("119500");
                    assertThat(first.ask("latest")).isEqualTo("119500");
                    assertThat(second.ask("latest")).isEqualTo("119500");
                    assertThat(first.ask("assets")).isEqualTo("1:880500");
                    assertThat(second.ask("assets")).isEqualTo("1:880500");
                    collector.close();
                    assertThat(first.ask("query")).isEqualTo("unavailable");
                    // 종료 정리 전에 프로세스가 끝난 경우에도 활동 TTL(15초)로 판별한다.
                    awaitCollectorStopped(redis);
                    assertThat(second.ask("latest")).isEqualTo("119500");
                }
            }
        } finally {
            if (database != null) database.getClass().getMethod("stop").invoke(database);
            if (testKeysOwned) for (String key : List.of("quote:TST009:metadata", "price:TST009")) redis.delete(key);
            connection.destroy();
        }
    }

    private static void awaitCollectorStopped(StringRedisTemplate redis) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (Boolean.TRUE.equals(redis.hasKey("coord:kiwoom:collector"))
                && System.nanoTime() < deadline) Thread.sleep(100);
        assertThat(redis.hasKey("coord:kiwoom:collector")).isFalse();
    }

    private static final class Node implements AutoCloseable {
        private final Process process;
        private final PrintWriter input;
        private final BlockingQueue<String> replies = new LinkedBlockingQueue<>();
        private final Deque<String> diagnostics = new ConcurrentLinkedDeque<>();
        Node(boolean collector, String database, String endpoint) throws Exception {
            this(collector, database, endpoint, 6380, 0);
        }
        Node(boolean collector, String database, String endpoint, int redisPort, int serverPort) throws Exception {
            String java = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
            process = new ProcessBuilder(java, "-Xmx256m", "-cp", System.getProperty("test.runtime.classpath"),
                    "com.stock_spoon.river_be.market.MultiProcessRedisNode", Boolean.toString(collector), database, endpoint,
                    Integer.toString(redisPort), Integer.toString(serverPort))
                    .redirectErrorStream(true).start();
            input = new PrintWriter(process.getOutputStream(), true);
            var reader = new Thread(() -> {
                try (var output = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    for (String line; (line = output.readLine()) != null;) {
                        diagnostics.addLast(line);
                        if (diagnostics.size() > 40) diagnostics.pollFirst();
                        if (line.startsWith("NODE:")) replies.add(line.substring(5));
                    }
                } catch (IOException ignored) { }
                try { replies.add("exited:" + process.waitFor()); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            }, "redis-node-output");
            reader.setDaemon(true); reader.start();
            try { assertThat(replies.poll(60, TimeUnit.SECONDS)).withFailMessage("노드 시작 실패: %s", diagnostics).isEqualTo("ready"); }
            catch (Throwable failure) { close(); throw failure; }
        }
        String ask(String command) throws Exception {
            return ask(command, 15);
        }
        String ask(String command, int timeoutSeconds) throws Exception {
            input.println(command);
            String response = replies.poll(timeoutSeconds, TimeUnit.SECONDS);
            assertThat(response).withFailMessage("노드 응답 없음: %s, 로그: %s", command, diagnostics).isNotNull();
            return response;
        }
        public void close() {
            if (!process.isAlive()) return;
            input.println("stop");
            try {
                if (!process.waitFor(10, TimeUnit.SECONDS)) {
                    process.destroy();
                    if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
                }
            } catch (InterruptedException error) { process.destroyForcibly(); Thread.currentThread().interrupt(); }
        }
        void kill() throws Exception {
            process.destroyForcibly();
            assertThat(process.waitFor(5, TimeUnit.SECONDS)).isTrue();
        }
    }
}
