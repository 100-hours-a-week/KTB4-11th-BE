package com.stock_spoon.river_be.market.kiwoom;

import java.util.Map;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** 일반 BE는 담당 BE에만 요청한다. 실패 시 키움 직접 조회로 우회하지 않는다. */
public final class RemoteKiwoomRestTransport implements KiwoomRestTransport {
    private static final ParameterizedTypeReference<Map<String, Object>> JSON = new ParameterizedTypeReference<>() {};
    private final RestClient client;
    private final StringRedisTemplate redis;
    private final String secret;

    public RemoteKiwoomRestTransport(RestClient client, StringRedisTemplate redis, String secret) {
        this.client = client;
        this.redis = redis;
        this.secret = secret;
    }

    public Map<String, Object> query(String apiId, String path, Map<String, String> body) {
        return forward(apiId, path, body, "/internal/kiwoom/query");
    }

    public Map<String, Object> metadata(String code) {
        return forward("ka10100", "/api/dostk/stkinfo", Map.of("stk_cd", code), "/internal/kiwoom/metadata");
    }

    private Map<String, Object> forward(String apiId, String path, Map<String, String> body, String endpoint) {
        if (client == null || secret == null || secret.isBlank()) throw unavailable();
        String owner = redis.opsForValue().get("coord:kiwoom:collector");
        if (owner == null || owner.isBlank()) throw unavailable();
        var request = CollectorQuery.from(apiId, path, body, owner);
        Map<String, Object> response;
        try {
            response = client.post().uri(endpoint).contentType(MediaType.APPLICATION_JSON)
                    .header(CollectorSecurityConfig.SECRET_HEADER, secret).body(request).retrieve().body(JSON);
        } catch (RestClientException error) {
            // HTTP 예외의 본문·주소·헤더는 호출자에게 전달하지 않는다.
            throw unavailable();
        }
        if (!owner.equals(redis.opsForValue().get("coord:kiwoom:collector"))) throw unavailable();
        return response;
    }

    private IllegalStateException unavailable() {
        return new IllegalStateException("키움 수집 담당 서버를 사용할 수 없습니다.");
    }
}
