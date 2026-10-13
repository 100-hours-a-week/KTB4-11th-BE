package com.stock_spoon.river_be.execution;

import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import java.time.*;
import java.math.BigDecimal;
import java.util.*;
import com.stock_spoon.river_be.market.kiwoom.KiwoomStockStream.*;
import com.stock_spoon.river_be.account.repository.AccountRepository;
import com.stock_spoon.river_be.order.repository.OrderRepository;
import com.stock_spoon.river_be.execution.repository.ExecutionRepository;
import com.stock_spoon.river_be.holding.repository.HoldingRepository;
import com.stock_spoon.river_be.user.repository.UserRepository;
import com.stock_spoon.river_be.order.dto.OrderCreateRequest;
import com.stock_spoon.river_be.order.entity.Order;

/** 두 운영 시작점의 HTTP/공유 DB 검증용. 시세만 결정적인 테스트 값으로 대체한다. */
public class ExecutionProcessNode {
    static final Instant NOW = Instant.parse("2026-10-13T01:00:00Z");
    public static void main(String[] args) throws Exception {
        Class<?> application = args[0].equals("execution") ? ExecutionApplication.class : com.stock_spoon.river_be.RiverBeApplication.class;
        try (var context = new SpringApplicationBuilder(application, Fixture.class).profiles(args[0]).run(
                "--server.address=127.0.0.1", "--server.port=0", "--spring.config.import=",
                "--spring.datasource.url=" + args[1], "--spring.datasource.driver-class-name=org.h2.Driver",
                "--spring.datasource.username=sa", "--spring.datasource.password=", "--spring.jpa.hibernate.ddl-auto=" + args[2],
                "--execution.url=" + args[3], "--execution.secret=process-test-key",
                "--kiwoom.stream.enabled=false", "--kiwoom.collector.enabled=false", "--market.kospi.refresh-enabled=false",
                "--kakao.client-id=test", "--kakao.client-secret=test", "--kakao.redirect-uri=http://localhost/callback",
                "--kakao.frontend-origin=http://localhost", "--jwt.secret=test-jwt-secret-that-is-longer-than-32-bytes")) {
            int port = ((org.springframework.boot.web.server.context.WebServerApplicationContext) context).getWebServer().getPort();
            System.out.println("#READY " + port);
            var input = new java.io.BufferedReader(new java.io.InputStreamReader(System.in));
            for (String line; (line = input.readLine()) != null;) {
                if (line.equals("stop")) break;
                var parts = line.split(" ");
                var tx = new org.springframework.transaction.support.TransactionTemplate(context.getBean(org.springframework.transaction.PlatformTransactionManager.class));
                switch (parts[0]) {
                    case "seed" -> {
                        var ids = tx.execute(status -> {
                            var user = context.getBean(UserRepository.class).save(new com.stock_spoon.river_be.user.entity.User("분리검증"));
                            var account = context.getBean(AccountRepository.class).saveAndFlush(new com.stock_spoon.river_be.account.entity.Account(user, "AI", 1000000));
                            var order = context.getBean(OrderRepository.class).saveAndFlush(Order.pendingLimit(account, "005930", Order.Side.BUY, 1, 70000, Order.Source.AI, null, NOW));
                            return user.getId() + " " + account.getId() + " " + order.getId();
                        });
                        System.out.println("#RESULT " + ids);
                    }
                    case "initial" -> {
                        context.getBean(com.stock_spoon.river_be.order.client.ExecutionGateway.class)
                                .initial(Long.parseLong(parts[1]), Long.parseLong(parts[2]), Long.parseLong(parts[3]));
                        System.out.println("#RESULT initial");
                    }
                    case "market" -> {
                        var request = new OrderCreateRequest("005930", "buy", "market", null, 1L, "test", null, List.of(), 30.0, false);
                        long id = context.getBean(com.stock_spoon.river_be.order.client.ExecutionGateway.class)
                                .market(Long.parseLong(parts[1]), Long.parseLong(parts[2]), request);
                        var response = context.getBean(com.stock_spoon.river_be.order.service.OrderQueryService.class)
                                .response(Long.parseLong(parts[2]), id);
                        System.out.println("#RESULT " + response.orderStatus() + " " + response.executions().size());
                    }
                    case "state" -> {
                        var state = tx.execute(status -> {
                            long account = Long.parseLong(parts[1]);
                            return context.getBean(AccountRepository.class).findById(account).orElseThrow().getCashBalance() + " "
                                    + context.getBean(HoldingRepository.class).findByAccountIdAndStockCode(account, "005930").map(h -> h.getQuantity()).orElse(0L)
                                    + " " + context.getBean(ExecutionRepository.class).count();
                        });
                        System.out.println("#RESULT " + state);
                    }
                    default -> throw new IllegalArgumentException("unknown command");
                }
            }
        }
    }
    @TestConfiguration
    public static class Fixture {
        @Bean @Primary
        com.stock_spoon.river_be.market.MarketStream testStream() {
            return new com.stock_spoon.river_be.market.MarketStream() {
                public void updateSymbols(Set<String> symbols) {}
                public java.util.concurrent.CompletableFuture<Void> whenSubscribed(String symbol) { return java.util.concurrent.CompletableFuture.completedFuture(null); }
                public Optional<StockPrice> latest(String symbol) { return Optional.of(new StockPrice(symbol, BigDecimal.valueOf(70000), BigDecimal.ZERO, BigDecimal.ZERO, LocalTime.of(10,0), NOW)); }
                public Optional<OrderBook> latestOrderBook(String symbol) { return Optional.of(new OrderBook(symbol,
                        List.of(new QuoteLevel(BigDecimal.valueOf(70000), 100)), List.of(new QuoteLevel(BigDecimal.valueOf(69900), 100)), LocalTime.of(10,0), NOW)); }
            };
        }
        @Bean @Primary @Profile("execution")
        com.stock_spoon.river_be.execution.service.OrderExecutionService fixedExecution(AccountRepository accounts, OrderRepository orders, HoldingRepository holdings, ExecutionRepository executions) {
            return com.stock_spoon.river_be.execution.service.ExecutionTestFixture.withClock(accounts, orders, holdings, executions, Clock.fixed(NOW, ZoneOffset.UTC));
        }
        @Bean @Primary
        com.stock_spoon.river_be.order.service.OrderService fixedOrder(AccountRepository accounts, OrderRepository orders, HoldingRepository holdings) {
            return com.stock_spoon.river_be.order.service.OrderClockFixture.create(accounts, orders, holdings, Clock.fixed(NOW, ZoneOffset.UTC));
        }
        @Bean @Primary
        com.stock_spoon.river_be.order.validator.OrderMarketValidator testValidator() {
            return org.mockito.Mockito.mock(com.stock_spoon.river_be.order.validator.OrderMarketValidator.class);
        }
    }
}
