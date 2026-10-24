package com.stock_spoon.river_be.stock.controller;

import com.stock_spoon.river_be.market.kiwoom.KiwoomMarketClient;
import com.stock_spoon.river_be.stock.dto.StockNameResponse;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v2/stocks")
public class StockController {
    private static final Logger log = LoggerFactory.getLogger(StockController.class);
    private final KiwoomMarketClient market;

    public StockController(KiwoomMarketClient market) {
        this.market = market;
    }

    @GetMapping("/{stockCode}")
    public StockNameResponse get(@PathVariable String stockCode) {
        if (!stockCode.matches("[0-9A-Z]{6}")) {
            log.warn("event=stock_code_rejected stage=stock_code_validation code=INVALID_STOCK_CODE");
            throw new IllegalArgumentException("종목코드를 확인해주세요.");
        }
        var details = market.stockDetails(stockCode);
        return new StockNameResponse(details.stockCode(), details.stockName());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> invalidCode() {
        return error(HttpStatus.BAD_REQUEST, "INVALID_STOCK_CODE", "종목코드를 확인해주세요.");
    }

    @ExceptionHandler({IllegalStateException.class, org.springframework.web.client.RestClientException.class})
    ResponseEntity<Map<String, String>> unavailable() {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "STOCK_INFO_UNAVAILABLE", "종목 정보를 조회할 수 없습니다.");
    }

    private ResponseEntity<Map<String, String>> error(HttpStatus status, String code, String message) {
        var level = status.is5xxServerError() ? log.atError() : log.atWarn();
        level.log("event=stock_name_lookup_rejected status={} code={} stage=stock_exception_handler", status.value(), code);
        return ResponseEntity.status(status).body(Map.of("code", code, "message", message));
    }
}

