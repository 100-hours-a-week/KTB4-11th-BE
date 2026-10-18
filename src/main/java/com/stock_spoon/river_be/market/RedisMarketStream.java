package com.stock_spoon.river_be.market;

import com.stock_spoon.river_be.order.entity.Order;
import com.stock_spoon.river_be.order.repository.OrderRepository;

import com.stock_spoon.river_be.market.kiwoom.KiwoomStockStream.StockPrice;
import com.stock_spoon.river_be.market.kiwoom.KiwoomStockStream.OrderBook;
import com.stock_spoon.river_be.market.kiwoom.RedisCollectorHeartbeat;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
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

/** 일반 BE는 채널·시세를 공유하며, 고정 담당만 AI 시세 채널을 구독해 중계한다. */
@Component
@Primary
@ConditionalOnProperty(name = "kiwoom.stream.enabled", havingValue = "true")
public class RedisMarketStream implements MarketStream, MessageListener, SubscriptionListener {
    private static final Logger log = LoggerFactory.getLogger(RedisMarketStream.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final RedisQuoteStore quotes;
    private final AiQuoteAdapter collector;
    private final RedisCollectorHeartbeat heartbeat;
    private final RedisMessageListenerContainer channels;
    private final ApplicationEventPublisher events;
    private final OrderRepository orders;
    private final ThreadPoolExecutor callbacks = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(256), runnable -> {
                var thread = new Thread(runnable, "shared-quotes");
                thread.setDaemon(true);
                return thread;
            });
    private volatile Set<String> desired = Set.of();
    private final Set<String> attached = new HashSet<>();

    public RedisMarketStream(RedisQuoteStore quotes,
            AiQuoteAdapter collector, RedisCollectorHeartbeat heartbeat,
            @org.springframework.beans.factory.annotation.Qualifier("quoteListenerContainer")
            RedisMessageListenerContainer channels, ApplicationEventPublisher events,
            OrderRepository orders) {
        this.quotes = quotes;
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
                collector.updateSymbols(orders.findStockCodesByStatus(Order.Status.PENDING));
            } else {
                collector.updateSymbols(Set.of());
            }
        } catch (RuntimeException error) {
            log.warn("event=shared_quote_reconcile_failed causeType={}", error.getClass().getSimpleName());
        }
    }

    public Optional<StockPrice> latest(String code) { return quotes.usablePrice(code).or(() -> collector.latestPrice(code)); }
    public Optional<RedisQuoteStore.QuoteEvent> latestQuote(String code) { return quotes.usablePriceEvent(code); }
    public Optional<OrderBook> latestOrderBook(String code) { return quotes.usableOrderBook(code).or(() -> collector.latestOrderBook(code)); }
    public Optional<RedisQuoteStore.QuoteEvent> latestBookQuote(String code) { return quotes.usableBookEvent(code); }
    public void assertAvailable(String code) {
        if (quotes.activeSource(code).isEmpty()) throw new IllegalStateException("시세 수집 담당 또는 구독 연결을 사용할 수 없습니다.");
    }

    public void assertSource(String code, QuoteStore.Source source) {
        if (source == null || quotes.activeSource(code).filter(source::equals).isEmpty()) {
            throw new IllegalStateException("이전 시세 연결의 알림입니다.");
        }
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
        // 구독 ACK는 새로운 시세가 아니다. 저장 스냅샷으로 예약 주문을 체결하지 않는다.
    }

    @PreDestroy
    public void close() {
        channels.removeMessageListener(this);
        callbacks.shutdownNow();
    }
}
