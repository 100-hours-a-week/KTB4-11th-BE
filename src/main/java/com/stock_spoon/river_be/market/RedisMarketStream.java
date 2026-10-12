package com.stock_spoon.river_be.market;

import com.stock_spoon.river_be.order.entity.Order;
import com.stock_spoon.river_be.order.repository.OrderRepository;

import com.stock_spoon.river_be.market.kiwoom.KiwoomStockStream;
import com.stock_spoon.river_be.market.kiwoom.KiwoomStockStream.StockPrice;
import com.stock_spoon.river_be.market.kiwoom.KiwoomStockStream.OrderBook;
import com.stock_spoon.river_be.market.kiwoom.RedisCollectorHeartbeat;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ArrayBlockingQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.SubscriptionListener;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** 일반 BE는 채널·시세를 공유하며, 고정 담당만 키움 구독을 변경한다. */
@Component
@Primary
@ConditionalOnProperty(name = "kiwoom.stream.enabled", havingValue = "true")
public class RedisMarketStream implements MarketStream, MessageListener, SubscriptionListener {
    private static final Logger log = LoggerFactory.getLogger(RedisMarketStream.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final RedisQuoteStore quotes;
    private final RedisSubscriptionDemand demands;
    private final KiwoomStockStream collector;
    private final RedisCollectorHeartbeat heartbeat;
    private final RedisMessageListenerContainer channels;
    private final ApplicationEventPublisher events;
    private final com.stock_spoon.river_be.order.repository.OrderRepository orders;
    private final ConcurrentHashMap<String, CompletableFuture<Void>> waiting = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor callbacks = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(256), runnable -> {
                var thread = new Thread(runnable, "shared-quotes");
                thread.setDaemon(true);
                return thread;
            });
    private volatile Set<String> desired = Set.of();
    private final Set<String> attached = new HashSet<>();

    public RedisMarketStream(RedisQuoteStore quotes, RedisSubscriptionDemand demands,
            KiwoomStockStream collector, RedisCollectorHeartbeat heartbeat,
            @org.springframework.beans.factory.annotation.Qualifier("quoteListenerContainer")
            RedisMessageListenerContainer channels, ApplicationEventPublisher events,
            com.stock_spoon.river_be.order.repository.OrderRepository orders) {
        this.quotes = quotes;
        this.demands = demands;
        this.collector = collector;
        this.heartbeat = heartbeat;
        this.channels = channels;
        this.events = events;
        this.orders = orders;
        channels.setTaskExecutor(callbacks);
    }

    public synchronized void updateSymbols(Set<String> symbols) {
        if (symbols.stream().anyMatch(code -> code == null || !code.matches("[0-9A-Z]{6}"))) {
            throw new IllegalArgumentException("종목코드를 확인하세요.");
        }
        desired = Set.copyOf(symbols);
        for (String code : Set.copyOf(attached)) {
            if (!symbols.contains(code)) {
                channels.removeMessageListener(this, new ChannelTopic("quotes:" + code));
                attached.remove(code);
                var future = waiting.remove(code);
                if (future != null) future.completeExceptionally(new IllegalStateException("구독 수요가 종료되었습니다."));
            }
        }
        for (String code : symbols) {
            if (!attached.contains(code)) {
                channels.addMessageListener(this, new ChannelTopic("quotes:" + code));
                attached.add(code);
            }
        }
        reconcile();
    }

    @Scheduled(fixedDelay = 1000)
    public void reconcile() {
        try {
            if (heartbeat.ownsLease()) {
                // 임시 표시 해제는 DB 커밋 뒤다. 이 순서로 읽어 전환 순간에도 한쪽 수요를 확보한다.
                var combined = new HashSet<>(demands.symbols());
                combined.addAll(orders.findStockCodesByStatus(com.stock_spoon.river_be.order.entity.Order.Status.PENDING));
                collector.updateSymbols(combined);
            }
            for (var entry : waiting.entrySet()) {
                if (quotes.activeSource(entry.getKey()).isPresent()) {
                    if (waiting.remove(entry.getKey(), entry.getValue())) entry.getValue().complete(null);
                }
            }
        } catch (RuntimeException error) {
            waiting.values().forEach(future -> future.completeExceptionally(error));
            waiting.clear();
            log.warn("event=shared_quote_reconcile_failed causeType={}", error.getClass().getSimpleName());
        }
    }

    public CompletableFuture<Void> whenSubscribed(String code) {
        try {
            if (!desired.contains(code)) throw new IllegalStateException("시세 구독 수요가 없습니다.");
            if (quotes.activeSource(code).isPresent()) return CompletableFuture.completedFuture(null);
            return waiting.computeIfAbsent(code, ignored -> new CompletableFuture<>());
        } catch (RuntimeException error) {
            return CompletableFuture.failedFuture(error);
        }
    }

    public Optional<StockPrice> latest(String code) { return quotes.usablePrice(code); }
    public Optional<RedisQuoteStore.QuoteEvent> latestQuote(String code) { return quotes.usablePriceEvent(code); }
    public Optional<OrderBook> latestOrderBook(String code) { return quotes.usableOrderBook(code); }
    public Optional<RedisQuoteStore.QuoteEvent> latestBookQuote(String code) { return quotes.usableBookEvent(code); }
    public void retainRequest(String id, String code) { demands.retain(id, code); }
    public void renewRequest(String id, String code) { demands.renew(id, code); }
    public void releaseRequest(String id, String code) { demands.release(id, code); }
    public void assertRequest(String id, String code) { demands.assertRequest(id, code); }
    public void assertAvailable(String code) {
        if (quotes.activeSource(code).isEmpty()) throw new IllegalStateException("시세 수집 담당 또는 구독 연결을 사용할 수 없습니다.");
    }

    public void assertSource(String code, QuoteStore.Source source) {
        if (source == null || quotes.activeSource(code).filter(source::equals).isEmpty()) {
            throw new IllegalStateException("이전 시세 연결의 알림입니다.");
        }
    }

    public void assertRestFallbackAllowed() {
        // 최초 주문 판단은 구독 연결에서 현재가가 수신될 때 진행한다.
        throw new IllegalStateException("최초 현재가는 수집 담당의 웹소켓 수신을 기다립니다.");
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            var event = JSON.readValue(message.getBody(), RedisQuoteStore.QuoteEvent.class);
            if (!desired.contains(event.stockCode())
                    || !("quotes:" + event.stockCode()).equals(new String(message.getChannel(), StandardCharsets.UTF_8))
                    || event.source() == null
                    || !quotes.activeSource(event.stockCode()).filter(event.source()::equals).isPresent()) return;
            if ("price".equals(event.type()) && event.price() != null
                    && event.stockCode().equals(event.price().stockCode())
                    && event.receivedAt().equals(event.price().receivedAt())) events.publishEvent(event);
        } catch (RuntimeException error) {
            log.warn("event=shared_quote_event_failed causeType={}", error.getClass().getSimpleName());
        }
    }

    @Override
    public void onChannelSubscribed(byte[] channel, long count) {
        String name = new String(channel, StandardCharsets.UTF_8);
        if (!name.startsWith("quotes:")) return;
        String code = name.substring("quotes:".length());
        // 재구독 후 최신 값만 재판단한다. ACK 수신 스레드에서 DB 처리를 실행하지 않는다.
        try {
            callbacks.execute(() -> {
                try {
                    if (desired.contains(code)) quotes.usablePriceEvent(code).ifPresent(events::publishEvent);
                } catch (RuntimeException error) {
                    log.warn("event=shared_quote_recovery_failed causeType={}", error.getClass().getSimpleName());
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException error) {
            log.warn("event=shared_quote_recovery_overloaded");
        }
    }

    @PreDestroy
    public void close() {
        channels.removeMessageListener(this);
        waiting.values().forEach(future -> future.completeExceptionally(new IllegalStateException("시세 구독이 종료되었습니다.")));
        waiting.clear();
        callbacks.shutdownNow();
    }
}
