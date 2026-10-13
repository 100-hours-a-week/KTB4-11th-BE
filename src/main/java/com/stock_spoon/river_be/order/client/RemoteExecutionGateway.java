package com.stock_spoon.river_be.order.client;

import com.stock_spoon.river_be.execution.controller.InternalExecutionController.*;
import com.stock_spoon.river_be.order.dto.OrderCreateRequest;
import com.stock_spoon.river_be.order.exception.OrderException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.*;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import java.time.Duration;

@Service
@Profile("api")
public class RemoteExecutionGateway implements ExecutionGateway {
    private final RestClient client;
    public RemoteExecutionGateway(@Value("${execution.url:}") String url,
            @Value("${execution.secret:}") String secret) {
        var uri = java.net.URI.create(url);
        if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || !(uri.getPath().isEmpty() || "/".equals(uri.getPath())) || secret.isBlank())
            throw new IllegalStateException("체결 서버 URL과 전용 비밀키를 설정하세요.");
        var http = java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
                .followRedirects(java.net.http.HttpClient.Redirect.NEVER).build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(15));
        client = RestClient.builder().baseUrl(url).requestFactory(factory).defaultHeader("X-Execution-Key", secret).build();
    }
    public long market(long userId, long accountId, OrderCreateRequest request) {
        var command = new MarketCommand(java.util.UUID.randomUUID().toString(), userId, accountId, request);
        try { return post("market", command); }
        catch (RestClientResponseException error) {
            if (error.getStatusCode().is4xxClientError()) throw rejected(error);
            return resolve(command);
        }
        catch (RestClientException error) {
            return resolve(command);
        }
    }
    private long resolve(MarketCommand command) {
            try { return post("result", command); }
            catch (RestClientException unavailable) {
                throw new OrderException(HttpStatus.SERVICE_UNAVAILABLE, "EXECUTION_RESULT_UNKNOWN",
                        "체결 결과를 확인할 수 없습니다. 내부 요청 ID: " + command.requestId());
            }
    }
    private long post(String path, MarketCommand command) {
        var result = client.post().uri("/internal/execution/" + path).body(command).retrieve().body(Result.class);
        if (result == null || result.orderId() <= 0) throw new RestClientException("체결 응답을 확인하세요.");
        return result.orderId();
    }
    private OrderException rejected(RestClientResponseException error) {
        try {
            var body = tools.jackson.databind.json.JsonMapper.builder().build().readTree(error.getResponseBodyAsString());
            return new OrderException(HttpStatus.valueOf(error.getStatusCode().value()),
                    body.path("code").asText("EXECUTION_REJECTED"), body.path("message").asText("체결 요청이 거절되었습니다."));
        } catch (RuntimeException malformed) {
            return new OrderException(HttpStatus.SERVICE_UNAVAILABLE, "EXECUTION_RESULT_UNKNOWN", "체결 결과를 확인할 수 없습니다.");
        }
    }
    public void initial(long userId, long accountId, long orderId) {
        try {
            client.post().uri("/internal/execution/initial").body(new InitialCommand(userId, accountId, orderId))
                    .retrieve().toBodilessEntity();
        } catch (RestClientException error) {
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("event=initial_execution_deferred orderId={} causeType={}",
                    orderId, error.getClass().getSimpleName());
        }
    }
}
