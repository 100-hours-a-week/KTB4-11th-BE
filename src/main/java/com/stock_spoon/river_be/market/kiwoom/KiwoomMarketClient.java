package com.stock_spoon.river_be.market.kiwoom;

import java.math.BigDecimal;

import java.util.Map;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** 키움 시세 조회. 실전 주문 API는 호출하지 않는다. */
public class KiwoomMarketClient {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(KiwoomMarketClient.class);
    private final KiwoomRestTransport transport;
    private final StockMetadataCache metadata;

    public KiwoomMarketClient(RestClient client, KiwoomTokenProvider tokens) {
        this(new DirectKiwoomRestTransport(client, tokens, () -> true));
    }

    public KiwoomMarketClient(KiwoomRestTransport transport) {
        this(transport, null);
    }

    public KiwoomMarketClient(KiwoomRestTransport transport, StockMetadataCache metadata) {
        this.transport = transport;
        this.metadata = metadata;
    }

    /** ka10100: 주문 대상의 상장 시장과 종목 상태를 조회한다. */
    public StockInfo stockInfo(String stockCode) {
        Map<String, Object> response = stockInfoResponse(stockCode);
        if (!stockCode.equals(response.get("code"))
                || !(response.get("marketCode") instanceof String marketCode)
                || marketCode.isBlank()) {
            log.warn("event=kiwoom_response_invalid apiId=ka10100 stockCode={}", stockCode);
            throw new IllegalStateException("키움 종목정보 응답 형식이 올바르지 않습니다.");
        }
        String state = response.get("state") instanceof String value ? value : "";
        return new StockInfo(stockCode, marketCode, state,
                String.valueOf(response.getOrDefault("orderWarning", "")));
    }

    /** ka10001 주식기본정보요청의 현재가. 가격의 +/- 방향 표기는 제거한다. */
    public long currentPrice(String stockCode) {
        if (stockCode == null || !stockCode.matches("[0-9A-Z]{6}")) {
            throw new IllegalArgumentException("종목코드를 확인하세요.");
        }
        Map<String, Object> response;
        try {
            response = transport.query("ka10001", "/api/dostk/stkinfo", Map.of("stk_cd", stockCode));
        } catch (RestClientException error) {
            throw new IllegalStateException("키움 현재가 조회에 실패했습니다.");
        }
        if (response == null || !"0".equals(String.valueOf(response.get("return_code")))) {
            throw new IllegalStateException("키움 현재가 조회가 거부되었습니다.");
        }
        try {
            if (!stockCode.equals(response.get("stk_cd"))) throw new IllegalArgumentException();
            long price = number(response.get("cur_prc")).abs().longValueExact();
            if (price <= 0) throw new IllegalArgumentException();
            return price;
        } catch (RuntimeException error) {
            log.warn("event=kiwoom_response_invalid apiId=ka10001 stockCode={}", stockCode);
            throw new IllegalStateException("키움 현재가 응답 형식이 올바르지 않습니다.");
        }
    }

    /** 종료 거래일의 원주가 종가. 다른 날짜나 현재가로 대체하지 않는다. */
    public long closingPrice(String stockCode, java.time.LocalDate date) {
        if (stockCode == null || !stockCode.matches("[0-9A-Z]{6}") || date == null) {
            throw new IllegalArgumentException("종목코드와 거래일을 확인하세요.");
        }
        String day = date.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
        Map<String, Object> response;
        try {
            response = transport.query("ka10081", "/api/dostk/chart",
                    Map.of("stk_cd", stockCode, "base_dt", day, "upd_stkpc_tp", "0"));
        } catch (RestClientException error) {
            throw new IllegalStateException("키움 종가 조회에 실패했습니다.");
        }
        if (response == null || !"0".equals(String.valueOf(response.get("return_code")))
                || !stockCode.equals(response.get("stk_cd"))
                || !(response.get("stk_dt_pole_chart_qry") instanceof java.util.List<?> rows)) {
            throw new IllegalStateException("키움 종가 응답을 확인할 수 없습니다.");
        }
        for (Object row : rows) {
            if (row instanceof Map<?, ?> values && day.equals(values.get("dt"))) {
                try {
                    long price = number(values.get("cur_prc")).abs().longValueExact();
                    if (price > 0) return price;
                } catch (RuntimeException error) {
                    throw new IllegalStateException("키움 종가 형식이 올바르지 않습니다.");
                }
            }
        }
        throw new IllegalStateException("해당 거래일의 종가가 아직 없습니다.");
    }

    /** 업종코드 사전을 추측해 연결하지 않고, 종목정보의 업종명을 그대로 읽는다. */
    public StockDetails stockDetails(String stockCode) {
        var response = metadata == null ? stockInfoResponse(stockCode) : metadata(stockCode);
        if (!stockCode.equals(response.get("code")) || !(response.get("name") instanceof String name)
                || name.isBlank()) {
            log.warn("event=kiwoom_response_invalid apiId=ka10100 stockCode={} field=name", stockCode);
            throw new IllegalStateException("키움 종목명 응답 형식이 올바르지 않습니다.");
        }
        Object sector = response.get("upName");
        if (sector != null && !(sector instanceof String)) {
            log.warn("event=kiwoom_response_invalid apiId=ka10100 stockCode={} field=sector", stockCode);
            throw new IllegalStateException("키움 업종명 응답 형식이 올바르지 않습니다.");
        }
        return new StockDetails(stockCode, name, sector == null ? "" : ((String) sector).trim());
    }

    /** DB에 없는 종목 마스터를 확보한다. 종목정보 캐시를 사용하지 않는다. */
    public StockMaster stockMaster(String stockCode) {
        var response = stockInfoResponse(stockCode);
        if (!stockCode.equals(response.get("code"))) {
            throw new IllegalStateException("키움 종목코드가 일치하지 않습니다.");
        }
        String name = masterField(response.get("name"), 100, false);
        String marketCode = masterField(response.get("marketCode"), 10, false);
        String sector = masterField(response.get("upName"), 100, true);
        return new StockMaster(stockCode, name, marketCode, sector);
    }

    private String masterField(Object raw, int maxLength, boolean optional) {
        if (raw == null && optional) return null;
        if (!(raw instanceof String value)) throw new IllegalStateException("키움 종목정보 형식이 올바르지 않습니다.");
        String trimmed = value.trim();
        if (trimmed.isEmpty() && optional) return null;
        if (trimmed.isEmpty() || trimmed.length() > maxLength) {
            throw new IllegalStateException("키움 종목정보 길이가 올바르지 않습니다.");
        }
        return trimmed;
    }

    public record StockMaster(String stockCode, String stockName, String marketCode, String sector) {}

    Map<String, Object> metadata(String stockCode) {
        return metadata.load(stockCode, () -> transport.metadata(stockCode));
    }

    // 주문 검증과 보유 조회가 같은 ka10100 호출을 재사용하되 각자의 필수 필드만 검증한다.
    private Map<String, Object> stockInfoResponse(String stockCode) {
        if (stockCode == null || !stockCode.matches("[0-9A-Z]{6}")) throw new IllegalArgumentException("종목코드를 확인하세요.");
        Map<String, Object> response;
        try {
            response = transport.query("ka10100", "/api/dostk/stkinfo", Map.of("stk_cd", stockCode));
        } catch (RestClientException error) {
            throw new IllegalStateException("키움 종목정보 조회에 실패했습니다.");
        }
        if (response == null || !"0".equals(String.valueOf(response.get("return_code")))) {
            throw new IllegalStateException("키움 종목정보 조회가 거부되었습니다.");
        }
        log.debug("키움 종목정보 수신 apiId=ka10100 stockCode={}", stockCode);
        return response;
    }

    static String safeReturnCode(Map<String, Object> response) {
        String code = response == null ? "missing" : String.valueOf(response.get("return_code"));
        return code.matches("-?[0-9]{1,10}") ? code : "invalid";
    }

    static String safeDetailCode(Map<String, Object> response) {
        if (response == null) return "missing";
        var match = java.util.regex.Pattern.compile("\\[(\\d{3,5}):")
                .matcher(String.valueOf(response.get("return_msg")));
        return match.find() ? match.group(1) : "missing";
    }

    public record StockDetails(String stockCode, String stockName, String sector) {}

    private BigDecimal number(Object value) {
        if (!(value instanceof String) && !(value instanceof Number)) {
            throw new IllegalArgumentException();
        }
        return new BigDecimal(value.toString().trim().replace(",", ""));
    }


    public record StockInfo(String stockCode, String marketCode, String state,
                            String orderWarning) {}
}
