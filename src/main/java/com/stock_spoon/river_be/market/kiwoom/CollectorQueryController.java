package com.stock_spoon.river_be.market.kiwoom;

import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@ConditionalOnProperty(name = "kiwoom.collector.enabled", havingValue = "true")
public class CollectorQueryController {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CollectorQueryController.class);
    private final KiwoomRestTransport transport;
    private final RedisCollectorHeartbeat heartbeat;
    private final KiwoomMarketClient market;

    public CollectorQueryController(KiwoomRestTransport transport, RedisCollectorHeartbeat heartbeat, KiwoomMarketClient market) {
        this.transport = transport;
        this.heartbeat = heartbeat;
        this.market = market;
    }

    @PostMapping("/internal/kiwoom/metadata")
    public Map<String, Object> metadata(@RequestBody CollectorQuery request) {
        if (!"ka10100".equals(request.apiId())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        try { request.body(); }
        catch (IllegalArgumentException error) {
            log.warn("event=collector_request_rejected stage=metadata_request_validation causeType={}", error.getClass().getSimpleName());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        try {
            if (!heartbeat.ownsLease() || !heartbeat.ownerId().equals(request.collectorId())) throw new IllegalStateException();
            var response = market.metadata(request.stockCode());
            if (!heartbeat.ownsLease()) throw new IllegalStateException();
            return response;
        } catch (RuntimeException error) {
            log.warn("event=collector_request_failed stage=metadata_query apiId=ka10100 stockCode={} causeType={}",
                    request.stockCode(), error.getClass().getSimpleName());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "종목정보를 확보할 수 없습니다.");
        }
    }

    @PostMapping("/internal/kiwoom/query")
    public Map<String, Object> query(@RequestBody CollectorQuery request) {
        String path;
        Map<String, String> body;
        try {
            path = request.path();
            body = request.body();
        } catch (IllegalArgumentException error) {
            log.warn("event=collector_request_rejected stage=query_request_validation causeType={}", error.getClass().getSimpleName());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "조회 요청을 확인하세요.");
        }
        try {
            if (!heartbeat.ownsLease() || !heartbeat.ownerId().equals(request.collectorId())) throw new IllegalStateException();
            var response = transport.query(request.apiId(), path, body);
            if (!heartbeat.ownsLease()) throw new IllegalStateException();
            return response;
        } catch (RuntimeException error) {
            log.warn("event=collector_request_failed stage=transport_query apiId={} stockCode={} causeType={}",
                    request.apiId(), request.stockCode(), error.getClass().getSimpleName());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "수집 담당 조회를 사용할 수 없습니다.");
        }
    }
}
