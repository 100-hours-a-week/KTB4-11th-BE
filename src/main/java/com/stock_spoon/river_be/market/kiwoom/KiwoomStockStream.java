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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 서버 내부 시세 구독. 키움 원문, 토큰, 인증 정보는 로그에 기록하지 않는다. */
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
    private final List<String> symbols;
    // ponytail: 한 서버의 설정된 종목만 구독. 다중 서버/동적 종목은 구독 소유권부터 설계한다.
    private final Map<String, StockPrice> prices = new HashMap<>();
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
        this.symbols = symbols.stream().map(String::trim).filter(s -> !s.isEmpty()).distinct().toList();
        // 첫 단계는 KRX 보통 종목코드만 허용한다. NXT/SOR는 별도 합의 후 확장한다.
        if (this.symbols.stream().anyMatch(s -> !s.matches("[0-9A-Z]{6}"))
                || (enabled && this.symbols.isEmpty())) {
            throw new IllegalArgumentException("키움 구독 종목코드를 확인하세요.");
        }
        this.http = http;
        this.clock = clock;
        state = enabled ? State.DISCONNECTED : State.DISABLED;
    }

    public synchronized State state() {
        return state;
    }

    /** 연결 유효성과 가격의 시장 시각은 별개다. 장 상태/주문 허용 판단은 체결 엔진에서 한다. */
    public synchronized Optional<StockPrice> latest(String symbol) {
        if (state != State.SUBSCRIBED || session == null
                || !clock.instant().isBefore(session.lastMessage.plusSeconds(90))) {
            return Optional.empty();
        }
        return Optional.ofNullable(prices.get(symbol));
    }

    @Scheduled(fixedDelay = 5000)
    public synchronized void maintainConnection() {
        if (!enabled || stopped) {
            return;
        }
        Instant now = clock.instant();
        if (session != null) {
            boolean handshakeTimeout = state != State.SUBSCRIBED
                    && !now.isBefore(session.startedAt.plusSeconds(10));
            boolean idleTimeout = !now.isBefore(session.lastMessage.plusSeconds(90));
            if (handshakeTimeout || idleTimeout) {
                disconnect(session);
            }
            return;
        }
        try {
            var next = new Session(tokens.accessToken(), now);
            session = next;
            state = State.CONNECTING;
            http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5))
                    .buildAsync(URL, next).whenComplete((socket, error) -> {
                        synchronized (KiwoomStockStream.this) {
                            if (error != null) {
                                disconnect(next);
                            } else if (session != next || stopped) {
                                socket.abort();
                            }
                        }
                    });
        } catch (RuntimeException error) {
            if (session != null) {
                disconnect(session);
            }
            log.warn("키움 실시간 연결 준비에 실패했습니다. 다음 주기에 재시도합니다.");
        }
    }

    private synchronized void disconnect(Session failed) {
        if (session != failed) {
            return;
        }
        session = null;
        prices.clear();
        state = stopped ? State.STOPPED : State.DISCONNECTED;
        failed.token = null;
        if (failed.socket != null) {
            failed.socket.abort();
        }
        if (!stopped) {
            log.warn("키움 실시간 연결이 종료되었습니다. 다음 주기에 재구독합니다.");
        }
    }

    @PreDestroy
    public synchronized void close() {
        stopped = true;
        if (session != null) {
            disconnect(session);
        }
        prices.clear();
        state = State.STOPPED;
        http.shutdownNow();
    }

    public enum State { DISABLED, DISCONNECTED, CONNECTING, AUTHENTICATING, SUBSCRIBING, SUBSCRIBED, STOPPED }

    public record StockPrice(String stockCode, BigDecimal currentPrice, BigDecimal change,
            BigDecimal changeRate, LocalTime tradeTime, Instant receivedAt) {}

    private final class Session implements WebSocket.Listener {
        private String token;
        private final Instant startedAt;
        private Instant lastMessage;
        private WebSocket socket;
        private final StringBuilder fragments = new StringBuilder();
        private CompletableFuture<?> sends = CompletableFuture.completedFuture(null);

        private Session(String token, Instant now) {
            this.token = token;
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
                        accept(message);
                        lastMessage = clock.instant();
                    }
                } catch (RuntimeException error) {
                    disconnect(this);
                }
                if (session == this) {
                    socket.request(1);
                }
            }
            return null;
        }

        private void accept(String text) {
            JsonNode message = JSON.readTree(text);
            switch (message.path("trnm").asText()) {
                case "LOGIN" -> {
                    if (state != State.AUTHENTICATING) {
                        throw new IllegalStateException();
                    }
                    if (!"0".equals(message.path("return_code").asText())) {
                        tokens.invalidate(token);
                        throw new IllegalStateException();
                    }
                    token = null;
                    state = State.SUBSCRIBING;
                    send(JSON.writeValueAsString(Map.of("trnm", "REG", "grp_no", "1", "refresh", "1",
                            "data", List.of(Map.of("item", symbols, "type", List.of("0B"))))));
                }
                case "REG" -> {
                    if (state != State.SUBSCRIBING || !"0".equals(message.path("return_code").asText())) {
                        throw new IllegalStateException();
                    }
                    state = State.SUBSCRIBED;
                    log.info("키움 현재가 구독에 성공했습니다. 종목 수: {}", symbols.size());
                }
                case "PING" -> send(text);
                case "REAL" -> {
                    if (state != State.SUBSCRIBED || !message.path("data").isArray()) {
                        return;
                    }
                    for (JsonNode entry : message.path("data")) {
                        String code = entry.path("item").asText();
                        if (!"0B".equals(entry.path("type").asText()) || !symbols.contains(code)) {
                            continue;
                        }
                        JsonNode values = entry.path("values");
                        // 가격의 +/-는 방향 표기다. 가격은 양수, 전일대비/등락률 부호는 유지한다.
                        BigDecimal price = new BigDecimal(values.path("10").asText()).abs();
                        if (price.signum() <= 0) {
                            throw new IllegalArgumentException();
                        }
                        prices.put(code, new StockPrice(code, price,
                                new BigDecimal(values.path("11").asText()),
                                new BigDecimal(values.path("12").asText()),
                                LocalTime.parse(values.path("20").asText(), TIME), clock.instant()));
                    }
                }
                default -> { }
            }
        }

        @Override
        public CompletionStage<?> onClose(WebSocket socket, int statusCode, String reason) {
            disconnect(this);
            return null;
        }

        @Override
        public void onError(WebSocket socket, Throwable error) {
            disconnect(this);
        }
    }
}

