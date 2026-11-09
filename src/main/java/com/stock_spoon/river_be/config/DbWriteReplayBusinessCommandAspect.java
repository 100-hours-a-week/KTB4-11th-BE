package com.stock_spoon.river_be.config;

import com.stock_spoon.river_be.account.dto.AccountCreateRequest;
import com.stock_spoon.river_be.account.dto.AccountNameUpdateRequest;
import com.stock_spoon.river_be.competition.dto.CompetitionCreateRequest;
import com.stock_spoon.river_be.order.dto.OrderCancelRequest;
import com.stock_spoon.river_be.order.dto.OrderCreateRequest;
import com.stock_spoon.river_be.watchlist.dto.WatchlistAddRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Saves authenticated identity and deserialized inputs before the first database access. */
@Aspect
@Component
// @annotation argument binding requires Spring's ExposeInvocationInterceptor to run first.
// This still captures the command before controller code and service transactions execute.
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@ConditionalOnProperty(prefix = "db-write-replay", name = "enabled", havingValue = "true")
public class DbWriteReplayBusinessCommandAspect {
    private static final Logger log = LoggerFactory.getLogger(DbWriteReplayBusinessCommandAspect.class);
    private final DbWriteReplayRequestContext context;
    private final JsonMapper json;

    public DbWriteReplayBusinessCommandAspect(DbWriteReplayRequestContext context, JsonMapper json) {
        this.context = context;
        this.json = json;
    }

    @Around(value = "@annotation(command)", argNames = "invocation,command")
    public Object capture(ProceedingJoinPoint invocation, DbWriteReplayBusinessCommand command) throws Throwable {
        try {
            capture(command.value(), invocation.getArgs());
        } catch (Exception captureFailure) {
            // A serialization problem must not replace the endpoint's normal result.
            // Never put a JWT, request body, SQL binding or Idempotency-Key in this log.
            log.error("event=db_write_replay_command_capture_failed commandType={} failureType={}",
                    command.value(), captureFailure.getClass().getSimpleName());
        }
        return invocation.proceed();
    }

    private void capture(DbWriteReplayBusinessCommand.Type type, Object[] args) throws Exception {
        if (args.length == 0 || !(args[0] instanceof Jwt jwt)) {
            throw new IllegalArgumentException("Authenticated JWT is required for a replay command");
        }
        long userId = Long.parseLong(jwt.getSubject());
        if (userId <= 0) throw new IllegalArgumentException("Invalid replay command actor");
        String source = jwt.getClaimAsString("actor");
        if (source == null) source = "USER";

        Map<String, Object> target = new LinkedHashMap<>();
        Object payload;
        switch (type) {
            case ACCOUNT_CREATE -> payload = (AccountCreateRequest) args[1];
            case COMPETITION_CREATE -> payload = (CompetitionCreateRequest) args[1];
            case ACCOUNT_RENAME -> {
                target.put("accountId", (Long) args[1]);
                payload = (AccountNameUpdateRequest) args[2];
            }
            case WATCHLIST_ADD -> payload = (WatchlistAddRequest) args[1];
            case WATCHLIST_REMOVE -> {
                target.put("stockCode", (String) args[1]);
                payload = Map.of();
            }
            case ORDER_CREATE -> {
                target.put("accountId", (Long) args[1]);
                payload = (OrderCreateRequest) args[2];
            }
            case ORDER_CANCEL -> {
                target.put("accountId", (Long) args[1]);
                target.put("orderId", (Long) args[2]);
                payload = (OrderCancelRequest) args[3];
            }
            default -> throw new IllegalArgumentException("Unsupported replay command");
        }

        Map<String, Object> businessCommand = new LinkedHashMap<>();
        businessCommand.put("type", type.name());
        businessCommand.put("actor", Map.of("userId", userId, "source", source));
        businessCommand.put("target", target);
        businessCommand.put("payload", payload);

        // Detach DTOs and sort every object key before hashing the semantic command.
        Object detached = canonical(json.readValue(json.writeValueAsString(businessCommand), Object.class));
        String payloadHash = sha256(json.writeValueAsString(detached));
        String clientKey = context.clientIdempotencyKey();
        String keySource = clientKey == null || clientKey.isBlank() ? "REQUEST" : "CLIENT";
        // Do not include payloadHash: the Lambda receipt must reject a reused key with different input.
        String scope = json.writeValueAsString(canonical(Map.of(
                "actor", businessCommand.get("actor"), "type", type.name(), "target", target,
                "keySource", keySource, "key", keySource.equals("CLIENT") ? clientKey : context.requestReplayId())));
        context.captureBusinessCommand(new DbWriteReplayRequestContext.CapturedBusinessCommand(
                detached, sha256(scope), keySource, payloadHash));
    }

    private static Object canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((key, element) -> sorted.put(String.valueOf(key), canonical(element)));
            return sorted;
        }
        if (value instanceof List<?> list) return list.stream().map(DbWriteReplayBusinessCommandAspect::canonical).toList();
        return value;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required", impossible);
        }
    }
}
