package com.stock_spoon.river_be.market;

import com.stock_spoon.river_be.execution.entity.Execution;
import com.stock_spoon.river_be.execution.repository.ExecutionRepository;
import com.stock_spoon.river_be.execution.service.OrderExecutionService;
import com.stock_spoon.river_be.holding.entity.Holding;
import com.stock_spoon.river_be.holding.repository.HoldingRepository;
import com.stock_spoon.river_be.order.entity.Order;
import com.stock_spoon.river_be.order.exception.OrderException;
import com.stock_spoon.river_be.order.repository.OrderRepository;
import com.stock_spoon.river_be.order.service.OrderService;
import com.stock_spoon.river_be.order.service.OrderSubscriptionService;

import com.stock_spoon.river_be.RiverBeApplication;
import com.stock_spoon.river_be.market.kiwoom.*;
import java.io.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.context.event.EventListener;
import static org.mockito.Mockito.*;

/** 테스트 클래스 경로에서만 실행하는 노드. 운영에 테스트 엔드포인트를 추가하지 않는다. */
public class MultiProcessRedisNode {
    public static void main(String[] args) throws Exception {
        try (var context = new SpringApplicationBuilder(RiverBeApplication.class, Fixture.class).run(
                "--server.address=127.0.0.1", "--server.port=" + args[4], "--spring.config.import=",
                "--spring.main.allow-bean-definition-overriding=true",
                "--spring.datasource.url=" + args[1], "--spring.datasource.driver-class-name=org.h2.Driver",
                "--spring.datasource.username=sa", "--spring.datasource.password=",
                "--spring.jpa.hibernate.ddl-auto=" + (Boolean.parseBoolean(args[0]) ? "update" : "validate"),
                "--spring.data.redis.host=localhost", "--spring.data.redis.port=" + args[3],
                "--spring.data.redis.timeout=3s", "--spring.data.redis.connect-timeout=3s",
                "--kiwoom.collector.enabled=" + args[0], "--kiwoom.stream.enabled=true",
                "--kiwoom.collector.url=" + args[2], "--kiwoom.collector.secret=test-only-process-secret",
                "--kiwoom.app-key=", "--kiwoom.app-secret=", "--market.kospi.refresh-enabled=false",
                "--kakao.client-id=test", "--kakao.client-secret=test", "--kakao.redirect-uri=http://localhost/callback",
                "--kakao.frontend-origin=http://localhost", "--jwt.secret=test-jwt-secret-that-is-longer-than-32-bytes")) {
            var fixture = context.getBean(Fixture.class);
            var stream = context.getBean(RedisMarketStream.class);
            var market = context.getBean(KiwoomMarketClient.class);
            var heartbeat = context.getBean(RedisCollectorHeartbeat.class);
            var quotes = context.getBean(RedisQuoteStore.class);
            var server = (org.springframework.boot.web.server.context.WebServerApplicationContext) context;
            reply("ready");
            var input = new BufferedReader(new InputStreamReader(System.in));
            for (String command; (command = input.readLine()) != null;) {
                if (command.equals("stop")) break;
                try {
                    switch (command) {
                        case "endpoint" -> reply("http://localhost:" + server.getWebServer().getPort());
                        case "metadata" -> reply(market.stockDetails("TST009").stockName());
                        case "calls" -> reply(Integer.toString(fixture.calls.get()));
                        case "query" -> reply(Long.toString(market.currentPrice("TST009")));
                        case "badquery" -> {
                            var remote = new RemoteKiwoomRestTransport(org.springframework.web.client.RestClient.builder()
                                    .baseUrl(args[2]).build(), context.getBean(org.springframework.data.redis.core.StringRedisTemplate.class), "wrong-secret");
                            remote.query("ka10001", "/api/dostk/stkinfo", Map.of("stk_cd", "TST009"));
                            reply("unexpected-success");
                        }
                        case "owner" -> reply(Boolean.toString(heartbeat.ownsLease()));
                        case "create" -> {
                            try {
                                context.getBean(com.stock_spoon.river_be.order.service.OrderSubscriptionService.class)
                                        .create("TST009", () -> { throw new AssertionError("장애 중 주문 저장 실행됨"); });
                                reply("unexpected-success");
                            } catch (com.stock_spoon.river_be.order.exception.OrderException failure) {
                                reply(Integer.toString(failure.status().value()));
                            }
                        }
                        case "state" -> {
                            var orders = context.getBean(com.stock_spoon.river_be.order.repository.OrderRepository.class);
                            var executions = context.getBean(com.stock_spoon.river_be.execution.repository.ExecutionRepository.class);
                            var accounts = context.getBean(com.stock_spoon.river_be.account.repository.AccountRepository.class);
                            long pending = orders.findAll().stream().filter(order -> order.getStatus()
                                    == com.stock_spoon.river_be.order.entity.Order.Status.PENDING).count();
                            reply(pending + ":" + executions.count() + ":" + accounts.findAll().getFirst().getCashBalance());
                        }
                        case "cancel-extra" -> {
                            var account = context.getBean(com.stock_spoon.river_be.account.repository.AccountRepository.class)
                                    .findAll().getFirst();
                            var orders = context.getBean(com.stock_spoon.river_be.order.repository.OrderRepository.class);
                            var extra = orders.save(com.stock_spoon.river_be.order.entity.Order.pendingLimit(account, "TST009",
                                    com.stock_spoon.river_be.order.entity.Order.Side.BUY, 1, 120000,
                                    com.stock_spoon.river_be.order.entity.Order.Source.AI, null, Instant.now().minusSeconds(1)));
                            var cancelled = context.getBean(com.stock_spoon.river_be.order.service.OrderService.class)
                                    .cancel(account.getId(), extra.getId());
                            reply(cancelled.getStatus().name());
                        }
                        case "replay-low" -> {
                            var old = fixture.last;
                            var price = new KiwoomStockStream.StockPrice("TST009", new BigDecimal("119500"),
                                    BigDecimal.ZERO, BigDecimal.ZERO, LocalTime.of(10, 0), Instant.now());
                            context.publishEvent(new RedisQuoteStore.QuoteEvent("TST009", "price", price.receivedAt(),
                                    old.source(), price, null));
                            reply("ok");
                        }
                        case "new-session" -> {
                            if (!heartbeat.ownsLease()) throw new IllegalStateException("담당 아님");
                            fixture.session = UUID.randomUUID().toString();
                            quotes.remove("TST009");
                            quotes.register("TST009", new QuoteStore.Source(heartbeat.ownerId(), fixture.session), Instant.now());
                            reply("ok");
                        }
                        case "subscribe" -> { stream.updateSymbols(Set.of("TST009")); reply("ok"); }
                        case "seed-load" -> {
                            var user = context.getBean(com.stock_spoon.river_be.user.repository.UserRepository.class)
                                    .save(new com.stock_spoon.river_be.user.entity.User("load-test"));
                            var account = context.getBean(com.stock_spoon.river_be.account.repository.AccountRepository.class)
                                    .save(new com.stock_spoon.river_be.account.entity.Account(user, "load-test", 20000000));
                            var orders = new ArrayList<com.stock_spoon.river_be.order.entity.Order>();
                            for (int i = 0; i < 101; i++) orders.add(com.stock_spoon.river_be.order.entity.Order.pendingLimit(
                                    account, "TST009", com.stock_spoon.river_be.order.entity.Order.Side.BUY, 1,
                                    i == 100 ? 1 : 120000, com.stock_spoon.river_be.order.entity.Order.Source.AI, null,
                                    Instant.now().minusSeconds(1)));
                            context.getBean(com.stock_spoon.river_be.order.repository.OrderRepository.class).saveAll(orders);
                            reply("ok");
                        }
                        case "emit-load-high", "emit-load-low" -> {
                            if (!heartbeat.ownsLease()) throw new IllegalStateException("담당 아님");
                            var source = new QuoteStore.Source(heartbeat.ownerId(), fixture.session);
                            quotes.register("TST009", source, Instant.now());
                            for (int i = 0; i < 20; i++) quotes.savePrice(new KiwoomStockStream.StockPrice("TST009",
                                    new BigDecimal(command.equals("emit-load-high") ? "200000" : "119500"),
                                    BigDecimal.ZERO, BigDecimal.ZERO, LocalTime.of(10, 0), Instant.now()), source);
                            reply("ok");
                        }
                        case "load-high-received", "load-low-received" -> {
                            int expected = command.equals("load-high-received") ? 20 : 40;
                            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
                            while (fixture.events.size() < expected && System.nanoTime() < deadline) Thread.sleep(50);
                            reply(Integer.toString(fixture.events.size()));
                        }
                        case "load-assets" -> {
                            var executions = context.getBean(com.stock_spoon.river_be.execution.repository.ExecutionRepository.class);
                            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
                            while (executions.count() < 100 && System.nanoTime() < deadline) Thread.sleep(50);
                            var all = executions.findAll();
                            long uniqueOrders = all.stream().map(com.stock_spoon.river_be.execution.entity.Execution::getOrderId).distinct().count();
                            long quantity = context.getBean(com.stock_spoon.river_be.holding.repository.HoldingRepository.class)
                                    .findAll().stream().mapToLong(com.stock_spoon.river_be.holding.entity.Holding::getQuantity).sum();
                            long cash = context.getBean(com.stock_spoon.river_be.account.repository.AccountRepository.class)
                                    .findAll().getFirst().getCashBalance();
                            reply(all.size() + ":" + uniqueOrders + ":" + quantity + ":" + cash);
                        }
                        case "seed" -> {
                            var user = context.getBean(com.stock_spoon.river_be.user.repository.UserRepository.class)
                                    .save(new com.stock_spoon.river_be.user.entity.User("process-test"));
                            var account = context.getBean(com.stock_spoon.river_be.account.repository.AccountRepository.class)
                                    .save(new com.stock_spoon.river_be.account.entity.Account(user, "process-test", 1000000));
                            context.getBean(com.stock_spoon.river_be.order.repository.OrderRepository.class).save(
                                    com.stock_spoon.river_be.order.entity.Order.pendingLimit(account, "TST009",
                                            com.stock_spoon.river_be.order.entity.Order.Side.BUY, 1, 120000,
                                            com.stock_spoon.river_be.order.entity.Order.Source.AI, null, Instant.now().minusSeconds(1)));
                            reply("ok");
                        }
                        case "assets" -> {
                            var executions = context.getBean(com.stock_spoon.river_be.execution.repository.ExecutionRepository.class);
                            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                            while (executions.count() == 0 && System.nanoTime() < deadline) Thread.sleep(50);
                            var accounts = context.getBean(com.stock_spoon.river_be.account.repository.AccountRepository.class);
                            reply(executions.count() + ":" + accounts.findAll().getFirst().getCashBalance());
                        }
                        case "emit", "emit-high" -> {
                            if (!heartbeat.ownsLease()) throw new IllegalStateException("담당 아님");
                            var source = new QuoteStore.Source(heartbeat.ownerId(), fixture.session);
                            quotes.register("TST009", source, Instant.now());
                            quotes.savePrice(new KiwoomStockStream.StockPrice("TST009",
                                    new BigDecimal(command.equals("emit-high") ? "200000" : "119500"),
                                    BigDecimal.ZERO, BigDecimal.ZERO, LocalTime.of(10, 0), Instant.now()), source);
                            reply("ok");
                        }
                        case "event" -> {
                            var event = fixture.events.poll(5, TimeUnit.SECONDS);
                            reply(event == null ? "missing" : event.price().currentPrice().toPlainString());
                        }
                        case "clear-events" -> { fixture.events.clear(); reply("ok"); }
                        case "latest" -> reply(stream.latest("TST009")
                                .map(price -> price.currentPrice().toPlainString()).orElse("empty"));
                        default -> throw new IllegalArgumentException("unknown command");
                    }
                } catch (RuntimeException failure) { reply("unavailable"); }
            }
        }
    }

    private static void reply(String message) { System.out.println("NODE:" + message); System.out.flush(); }

    @TestConfiguration(proxyBeanMethods = false)
    static class Fixture {
        @Bean static org.springframework.beans.factory.config.BeanPostProcessor fixedMarketClock() {
            var fixed = Clock.fixed(LocalDate.now(ZoneId.of("Asia/Seoul")).atTime(10, 0)
                    .atZone(ZoneId.of("Asia/Seoul")).toInstant(), ZoneOffset.UTC);
            return new org.springframework.beans.factory.config.BeanPostProcessor() {
                public Object postProcessBeforeInitialization(Object bean, String name) {
                    if (bean instanceof com.stock_spoon.river_be.order.service.OrderService
                            || bean instanceof com.stock_spoon.river_be.execution.service.OrderExecutionService) {
                        org.springframework.test.util.ReflectionTestUtils.setField(bean, "clock", fixed);
                    }
                    return bean;
                }
            };
        }
        final AtomicInteger calls = new AtomicInteger();
        final BlockingQueue<RedisQuoteStore.QuoteEvent> events = new LinkedBlockingQueue<>();
        volatile RedisQuoteStore.QuoteEvent last;
        String session = "process-test-session";
        @Bean(name = "kiwoomStockStream") KiwoomStockStream fakeExternalWebSocket() { return mock(KiwoomStockStream.class); }
        @Bean @Primary
        @ConditionalOnProperty(name = "kiwoom.collector.enabled", havingValue = "true")
        KiwoomRestTransport fakeExternalRest() {
            return (api, path, body) -> {
                calls.incrementAndGet();
                return Map.of("return_code", 0, "code", "TST009", "name", "TEST STOCK",
                        "marketCode", "0", "upName", "TEST", "cur_prc", "119500", "stk_cd", "TST009");
            };
        }
        @EventListener @org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
        void received(RedisQuoteStore.QuoteEvent event) {
            if ("TST009".equals(event.stockCode()) && event.price() != null) { last = event; events.add(event); }
        }
    }
}
