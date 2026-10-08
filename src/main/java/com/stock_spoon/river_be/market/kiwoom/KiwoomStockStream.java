package com.stock_spoon.river_be.market.kiwoom;

import jakarta.annotation.PreDestroy;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 서버 내부 시세 구독. 전체 키움 메시지·인증 정보는 기록하지 않고, 숫자 변환 실패값만 제한적으로 기록한다. */
public class KiwoomStockStream {
    private static final Logger log = LoggerFactory.getLogger(KiwoomStockStream.class);
    private static final URI URL = URI.create("wss://api.kiwoom.com:10000/api/dostk/websocket");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HHmmss")
            .withResolverStyle(ResolverStyle.STRICT);
    private final KiwoomTokenProvider tokens;
    private final HttpClient http;
    private final Clock clock;
    private final boolean enabled;
    private final Set<String> symbols = new HashSet<>();
    private final Set<String> registered = new HashSet<>();
    private final Map<String, CompletableFuture<Void>> registrations = new HashMap<>();
    private final Map<String, StockPrice> prices = new HashMap<>();
    private final Map<String, OrderBook> books = new HashMap<>();
    private java.util.function.Consumer<StockPrice> priceListener = ignored -> {};
    private Session session;
    private State state;
    private boolean stopped;

    public KiwoomStockStream(KiwoomTokenProvider tokens, boolean enabled, List<String> symbols) {
        this(tokens, enabled, symbols,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
                Clock.systemUTC());
    }

    KiwoomStockStream(KiwoomTokenProvider tokens, boolean enabled, List<String> symbols,
            HttpClient http, Clock clock) {
        this.tokens = tokens;
        this.enabled = enabled;
        this.symbols.addAll(symbols.stream().map(String::trim).filter(s -> !s.isEmpty()).toList());
        // 첫 단계는 KRX 보통 종목코드만 허용한다. NXT/SOR는 별도 합의 후 확장한다.
        if (this.symbols.stream().anyMatch(s -> !s.matches("[0-9A-Z]{6}"))) {
            throw new IllegalArgumentException("키움 구독 종목코드를 확인하세요.");
        }
        this.http = http;
        this.clock = clock;
        state = enabled ? State.DISCONNECTED : State.DISABLED;
    }

    /** Bean 생성 시 연결한다. 체결 처리는 스트림 잠금 밖에서 호출한다. */
    public synchronized void setPriceListener(java.util.function.Consumer<StockPrice> listener) {
        priceListener = java.util.Objects.requireNonNull(listener);
    }

    public synchronized State state() {
        return state;
    }

    /** 단일 BE에서 DB 대기 주문과 접수 중인 주문을 합친 종목 목록으로 갱신한다. */
    public synchronized void updateSymbols(Set<String> desired) {
        if (desired.stream().anyMatch(s -> s == null || !s.matches("[0-9A-Z]{6}"))) {
            throw new IllegalArgumentException("키움 구독 종목코드를 확인하세요.");
        }
        symbols.clear();
        symbols.addAll(desired);
        prices.keySet().retainAll(desired);
        books.keySet().retainAll(desired);
        registrations.entrySet().removeIf(entry -> {
            if (desired.contains(entry.getKey())) return false;
            entry.getValue().completeExceptionally(new IllegalStateException("구독 수요가 종료되었습니다."));
            return true;
        });
        if (symbols.isEmpty()) {
            closeWhenIdle();
            return;
        }
        if (session == null && enabled && !stopped) {
            CompletableFuture.runAsync(this::maintainConnection);
        }
        synchronizeSubscriptions();
    }

    private synchronized void closeWhenIdle() {
        Session idle = session;
        if (idle == null) return;
        session = null;
        registered.clear();
        prices.clear();
        books.clear();
        state = stopped ? State.STOPPED : State.DISCONNECTED;
        log.info("event=kiwoom_stream_idle_closed");
        idle.token = null;
        registrations.values().forEach(future -> future.completeExceptionally(
                new IllegalStateException("활성 주문이 없어 시세 연결을 종료했습니다.")));
        registrations.clear();
        if (idle.socket != null) {
            idle.sends.thenCompose(ignored -> idle.socket.sendClose(
                    WebSocket.NORMAL_CLOSURE, "no active subscriptions"))
                    .orTimeout(2, java.util.concurrent.TimeUnit.SECONDS)
                    .whenComplete((ignored, error) -> {
                        if (error != null) idle.socket.abort();
                    });
        }
    }

    public synchronized CompletableFuture<Void> whenSubscribed(String symbol) {
        if (!enabled || stopped || !symbols.contains(symbol)) {
            return CompletableFuture.failedFuture(new IllegalStateException("시세 구독을 사용할 수 없습니다."));
        }
        if (isRegistered(symbol)) return CompletableFuture.completedFuture(null);
        return registrations.computeIfAbsent(symbol, ignored -> new CompletableFuture<>());
    }

    private boolean isRegistered(String symbol) {
        return session != null && session.authenticated && registered.contains(symbol)
                && symbols.contains(symbol)
                && !("REMOVE".equals(session.control) && session.controlSymbols.contains(symbol))
                && clock.instant().isBefore(session.lastMessage.plusSeconds(90));
    }

    /** 응답에 요청 ID가 없으므로 REG/REMOVE는 한 번에 하나씩 보내 응답을 대응시킨다. */
    private void synchronizeSubscriptions() {
        if (session == null || !session.authenticated || session.control != null) return;
        var removed = new HashSet<>(registered);
        removed.removeAll(symbols);
        var added = new HashSet<>(symbols);
        added.removeAll(registered);
        if (removed.isEmpty() && added.isEmpty()) {
            state = State.SUBSCRIBED;
            return;
        }
        session.control = removed.isEmpty() ? "REG" : "REMOVE";
        session.controlSymbols = Set.copyOf(removed.isEmpty() ? added : removed);
        session.controlStartedAt = clock.instant();
        state = State.SUBSCRIBING;
        var packet = new HashMap<String, Object>();
        packet.put("trnm", session.control);
        packet.put("grp_no", "1");
        if ("REG".equals(session.control)) packet.put("refresh", "1");
        packet.put("data", List.of(Map.of("item", session.controlSymbols.stream().sorted().toList(),
                "type", List.of("0B", "0D"))));
        session.send(JSON.writeValueAsString(packet));
    }

    /** 연결 유효성과 가격의 시장 시각은 별개다. */
    public synchronized Optional<StockPrice> latest(String symbol) {
        if (!isRegistered(symbol)) {
            return Optional.empty();
        }
        return Optional.ofNullable(prices.get(symbol));
    }

    /** 호가 시각/수신 시각은 별도로 확인해야 한다. 연결 생존만으로 체결 가능성을 보장하지 않는다. */
    public synchronized Optional<OrderBook> latestOrderBook(String symbol) {
        if (!isRegistered(symbol)) {
            return Optional.empty();
        }
        return Optional.ofNullable(books.get(symbol));
    }

    @Scheduled(fixedDelay = 5000)
    public void maintainConnection() {
        final Session next;
        synchronized (this) {
            if (!enabled || stopped) return;
            if (symbols.isEmpty()) return;
            Instant now = clock.instant();
            if (session != null) {
                boolean handshakeTimeout = !session.authenticated
                        && !now.isBefore(session.startedAt.plusSeconds(10));
                boolean controlTimeout = session.control != null
                        && !now.isBefore(session.controlStartedAt.plusSeconds(10));
                boolean idleTimeout = !now.isBefore(session.lastMessage.plusSeconds(90));
                if (handshakeTimeout || controlTimeout || idleTimeout) {
                    log.warn("event=kiwoom_stream_timeout handshake={} control={} idle={}", handshakeTimeout, controlTimeout, idleTimeout);
                    disconnect(session);
                }
                return;
            }
            next = new Session(now);
            session = next;
            state = State.CONNECTING;
            log.info("event=kiwoom_stream_connecting symbolCount={}", symbols.size());
        }
        try {
            // 토큰 HTTP 요청 중에도 주문의 구독 대기 시간과 시세 수신이 막히지 않게 한다.
            String token = tokens.accessToken();
            synchronized (this) {
                if (session != next || stopped) return;
                next.token = token;
                http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5))
                    .buildAsync(URL, next).whenComplete((socket, error) -> {
                        synchronized (KiwoomStockStream.this) {
                            if (error != null) {
                                log.warn("event=kiwoom_stream_connect_failed causeType={}", error.getClass().getSimpleName());
                                disconnect(next);
                            } else if (session != next || stopped) {
                                socket.abort();
                            }
                        }
                    });
            }
        } catch (RuntimeException error) {
            disconnect(next);
            log.warn("event=kiwoom_stream_prepare_failed causeType={}", error.getClass().getSimpleName());
        }
    }

    private synchronized void disconnect(Session failed) {
        if (session != failed) {
            return;
        }
        session = null;
        registered.clear();
        registrations.values().forEach(future -> future.completeExceptionally(
                new IllegalStateException("키움 시세 구독 연결이 종료되었습니다.")));
        registrations.clear();
        prices.clear();
        books.clear();
        state = stopped ? State.STOPPED : State.DISCONNECTED;
        failed.token = null;
        if (failed.socket != null) {
            failed.socket.abort();
        }
        if (!stopped) {
            log.warn("event=kiwoom_stream_disconnected");
        }
    }

    @PreDestroy
    public synchronized void close() {
        stopped = true;
        if (session != null) {
            disconnect(session);
        }
        prices.clear();
        books.clear();
        state = State.STOPPED;
        registrations.values().forEach(future -> future.completeExceptionally(
                new IllegalStateException("시세 구독이 종료되었습니다.")));
        registrations.clear();
        http.shutdownNow();
    }

    public enum State { DISABLED, DISCONNECTED, CONNECTING, AUTHENTICATING, SUBSCRIBING, SUBSCRIBED, STOPPED }

    public record StockPrice(String stockCode, BigDecimal currentPrice, BigDecimal change,
            BigDecimal changeRate, LocalTime tradeTime, Instant receivedAt) {}

    public record QuoteLevel(BigDecimal price, long quantity) {}

    public record OrderBook(String stockCode, List<QuoteLevel> asks, List<QuoteLevel> bids,
            LocalTime quoteTime, Instant receivedAt) {
        public OrderBook {
            asks = List.copyOf(asks);
            bids = List.copyOf(bids);
        }
    }

    private static BigDecimal parseDecimal(String code, String type, JsonNode values, int field, String name) {
        JsonNode value = values.path(Integer.toString(field));
        try {
            return new BigDecimal(value.asText());
        } catch (NumberFormatException error) {
            logNumberFailure(code, type, field, name, value, "BigDecimal", error);
            throw error;
        }
    }

    private static long parseQuantity(String code, JsonNode values, int field, String name) {
        JsonNode value = values.path(Integer.toString(field));
        try {
            return Long.parseLong(value.asText());
        } catch (NumberFormatException error) {
            logNumberFailure(code, "0D", field, name, value, "long", error);
            throw error;
        }
    }

    private static void logNumberFailure(String code, String type, int field, String name,
            JsonNode value, String targetType, NumberFormatException error) {
        String raw = value.asText();
        boolean truncated = raw.length() > 80;
        // 숫자 필드만 제한적으로 기록하고 제어문자를 이스케이프해 로그 줄바꿈을 방지한다.
        String escaped = JSON.writeValueAsString(raw.substring(0, Math.min(raw.length(), 80)));
        String valueState = value.isMissingNode() ? "missing" : value.isNull() ? "null" : "present";
        String side = "0D".equals(type) ? (field < 51 || field >= 61 && field < 71 ? "ask" : "bid") : "none";
        int level = "0D".equals(type) ? (field - 41) % 10 + 1 : 0;
        log.warn("event=kiwoom_stream_number_parse_failed stockCode={} messageType={} field={} fieldName={} "
                        + "side={} level={} valueState={} rawValue={} rawValueTruncated={} targetType={} causeType={}",
                code, type, field, name, side, level, valueState, escaped, truncated, targetType,
                error.getClass().getSimpleName());
    }
    private static List<QuoteLevel> levels(String code, JsonNode values, int priceStart, int quantityStart) {
        var result = new ArrayList<QuoteLevel>(10);
        for (int i = 0; i < 10; i++) {
            BigDecimal price = parseDecimal(code, "0D", values, priceStart + i, "price").abs();
            long quantity = parseQuantity(code, values, quantityStart + i, "quantity");
            // 0원/0주는 해당 단계에 주문이 없다는 뜻이다. 누락/비정상 값은 0으로 추정하지 않는다.
            if (quantity < 0 || (price.signum() == 0 && quantity > 0)) {
                throw new IllegalArgumentException();
            }
            result.add(new QuoteLevel(price, quantity));
        }
        return result;
    }

    private final class Session implements WebSocket.Listener {
        private String token;
        private final Instant startedAt;
        private Instant lastMessage;
        private WebSocket socket;
        private final StringBuilder fragments = new StringBuilder();
        private CompletableFuture<?> sends = CompletableFuture.completedFuture(null);
        private boolean authenticated;
        private String control;
        private Set<String> controlSymbols = Set.of();
        private Instant controlStartedAt;

        private Session(Instant now) {
            startedAt = now;
            lastMessage = now;
        }

        @Override
        public void onOpen(WebSocket socket) {
            synchronized (KiwoomStockStream.this) {
                this.socket = socket;
                if (session != this || stopped) {
                    socket.abort();
                    token = null;
                    return;
                }
                state = State.AUTHENTICATING;
                log.info("event=kiwoom_stream_opened");
                send(JSON.writeValueAsString(Map.of("trnm", "LOGIN", "token", token)));
                socket.request(1);
            }
        }

        private void send(String text) {
            sends = sends.thenCompose(ignored -> socket.sendText(text, true))
                    .orTimeout(5, java.util.concurrent.TimeUnit.SECONDS);
            sends.whenComplete((ignored, error) -> {
                if (error != null) {
                    disconnect(this);
                }
            });
        }

        @Override
        public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            var receivedPrices = new ArrayList<StockPrice>();
            synchronized (KiwoomStockStream.this) {
                if (session != this || stopped) {
                    return null;
                }
                try {
                    if (fragments.length() + data.length() > 1_048_576) {
                        throw new IllegalArgumentException();
                    }
                    fragments.append(data);
                    if (last) {
                        String message = fragments.toString();
                        fragments.setLength(0);
                        accept(message, receivedPrices);
                        lastMessage = clock.instant();
                    }
                } catch (RuntimeException error) {
                    log.warn("event=kiwoom_stream_protocol_failed causeType={}", error.getClass().getSimpleName());
                    receivedPrices.clear();
                    disconnect(this);
                }
            }
            // ponytail: v1은 수신 순서대로 동기 처리한다. 처리량이 늘면 크기를 제한한 순차 큐로 분리한다.
            for (StockPrice price : receivedPrices) {
                try {
                    priceListener.accept(price);
                } catch (RuntimeException error) {
                    // DB 체결 오류는 시세 프로토콜 오류가 아니므로 연결을 끊지 않는다.
                    log.warn("event=kiwoom_stream_price_processing_failed stockCode={} causeType={}", price.stockCode(), error.getClass().getSimpleName());
                }
            }
            synchronized (KiwoomStockStream.this) {
                if (session == this) socket.request(1);
            }
            return null;
        }

        private void accept(String text, List<StockPrice> receivedPrices) {
            JsonNode message = JSON.readTree(text);
            switch (message.path("trnm").asText()) {
                case "LOGIN" -> {
                    if (state != State.AUTHENTICATING) {
                        throw new IllegalStateException();
                    }
                    if (!"0".equals(message.path("return_code").asText())) {
                        log.warn("event=kiwoom_stream_login_rejected returnCode={}", KiwoomMarketClient.safeReturnCode(Map.of("return_code", message.path("return_code").asText())));
                        tokens.invalidate(token);
                        throw new IllegalStateException();
                    }
                    token = null;
                    authenticated = true;
                    log.info("event=kiwoom_stream_authenticated");
                    synchronizeSubscriptions();
                }
                case "REG", "REMOVE" -> {
                    if (!message.path("trnm").asText().equals(control)
                            || !"0".equals(message.path("return_code").asText())) {
                        log.warn("event=kiwoom_stream_subscription_rejected operation={} returnCode={}", control, KiwoomMarketClient.safeReturnCode(Map.of("return_code", message.path("return_code").asText())));
                        throw new IllegalStateException();
                    }
                    if ("REG".equals(control)) registered.addAll(controlSymbols);
                    else registered.removeAll(controlSymbols);
                    log.info("event=kiwoom_stream_subscription_updated operation={} symbolCount={}", control, controlSymbols.size());
                    control = null;
                    controlSymbols = Set.of();
                    lastMessage = clock.instant();
                    registrations.entrySet().removeIf(entry -> {
                        if (!isRegistered(entry.getKey())) return false;
                        entry.getValue().complete(null);
                        return true;
                    });
                    synchronizeSubscriptions();
                }
                case "PING" -> send(text);
                case "REAL" -> {
                    if (!authenticated || !message.path("data").isArray()) {
                        return;
                    }
                    for (JsonNode entry : message.path("data")) {
                        String type = entry.path("type").asText();
                        String code = entry.path("item").asText();
                        if (!isRegistered(code)) {
                            continue;
                        }
                        JsonNode values = entry.path("values");
                        if ("0D".equals(type)) {
                            books.put(code, new OrderBook(code, levels(code, values, 41, 61), levels(code, values, 51, 71),
                                    LocalTime.parse(values.path("21").asText(), TIME), clock.instant()));
                            continue;
                        }
                        if (!"0B".equals(type)) {
                            continue;
                        }
                        // 가격의 +/-는 방향 표기다. 가격은 양수, 전일대비/등락률 부호는 유지한다.
                        BigDecimal price = parseDecimal(code, type, values, 10, "currentPrice").abs();
                        if (price.signum() <= 0) {
                            throw new IllegalArgumentException();
                        }
                        var received = new StockPrice(code, price,
                                parseDecimal(code, type, values, 11, "change"),
                                parseDecimal(code, type, values, 12, "changeRate"),
                                LocalTime.parse(values.path("20").asText(), TIME), clock.instant());
                        prices.put(code, received);
                        receivedPrices.add(received);
                    }
                }
                default -> { }
            }
        }

        @Override
        public CompletionStage<?> onClose(WebSocket socket, int statusCode, String reason) {
            log.info("event=kiwoom_stream_closed closeStatus={}", statusCode);
            disconnect(this);
            return null;
        }

        @Override
        public void onError(WebSocket socket, Throwable error) {
            log.warn("event=kiwoom_stream_failed causeType={}", error.getClass().getSimpleName());
            disconnect(this);
        }
    }
}

