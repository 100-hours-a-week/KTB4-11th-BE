package com.stock_spoon.river_be.market;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/market/indices")
public class KospiIndexController {
    private final KospiIndexService service;

    public KospiIndexController(KospiIndexService service) {
        this.service = service;
    }

    @GetMapping("/kospi")
    public ResponseEntity<?> kospi() {
        return service.latest().<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body(Map.of("code", "MARKET_DATA_UNAVAILABLE",
                                "message", "코스피 지수를 아직 조회하지 못했습니다.")));
    }
}