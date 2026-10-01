package com.stock_spoon.river_be.account.service;

import com.stock_spoon.river_be.account.dto.AccountHoldingsResponse;
import com.stock_spoon.river_be.account.dto.AccountHoldingsResponse.Item;
import com.stock_spoon.river_be.account.exception.AccountException;
import com.stock_spoon.river_be.account.repository.AccountRepository;
import com.stock_spoon.river_be.market.kiwoom.KiwoomMarketClient;
import com.stock_spoon.river_be.order.ExecutionRepository;
import com.stock_spoon.river_be.order.HoldingRepository;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** DB 스냅샷을 읽은 뒤 트랜잭션 밖에서 키움 REST를 조회한다. 자동 시세 갱신은 하지 않는다. */
@Service
public class AccountHoldingsService {
    private static final Logger log = LoggerFactory.getLogger(AccountHoldingsService.class);
    private final AccountRepository accounts;
    private final HoldingRepository holdings;
    private final ExecutionRepository executions;
    private final KiwoomMarketClient market;
    private final TransactionTemplate read;

    public AccountHoldingsService(AccountRepository accounts, HoldingRepository holdings,
            ExecutionRepository executions, KiwoomMarketClient market, PlatformTransactionManager transactions) {
        this.accounts = accounts;
        this.holdings = holdings;
        this.executions = executions;
        this.market = market;
        read = new TransactionTemplate(transactions);
        read.setReadOnly(true);
    }

    public AccountHoldingsResponse list(long userId, long accountId, String sort, String order, String limit) {
        long started = System.nanoTime();
        String criterion = sort == null ? "latest_purchase" : sort;
        String direction = order == null ? "desc" : order;
        int count = parseLimit(limit);
        if (!List.of("latest_purchase", "return_rate", "market_value").contains(criterion)
                || !List.of("asc", "desc").contains(direction)) throw invalidQuery();
        log.debug("보유조회 시작 accountId={} sort={} order={} limit={}", accountId, criterion, direction, limit);

        // 1. 본인 소유 활성 계좌인지 먼저 확인한다. 실패하면 외부 시세는 조회하지 않는다.
        List<Owned> owned = read.execute(status -> {
            if (accounts.findByIdAndUserIdAndActiveTrue(accountId, userId).isEmpty()) {
                log.warn("보유조회 계좌 접근 실패 accountId={} code=ACCOUNT_NOT_FOUND", accountId);
                throw new AccountException(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", "계좌를 찾을 수 없습니다.");
            }
            var rows = holdings.findAllByAccountIds(List.of(accountId));
            var times = new HashMap<String, Instant>();
            if (criterion.equals("latest_purchase") && !rows.isEmpty()) {
                for (var time : executions.findLastBuyTimes(accountId, rows.stream().map(h -> h.getStockCode()).toList())) {
                    times.put(time.getStockCode(), time.getLastPurchasedAt());
                }
            }
            // 엔티티 대신 값만 복사하여 외부 요청 중 지연 로딩과 긴 DB 트랜잭션을 피한다.
            return rows.stream().map(h -> new Owned(h.getStockCode(), h.getQuantity(), h.getTotalCost(),
                    times.get(h.getStockCode()))).toList();
        });
        log.debug("보유조회 DB 읽기 완료 accountId={} candidates={}", accountId, owned.size());

        // 2. 최근 매수순은 먼저 제한할 수 있다. 평가순은 모든 후보의 현재가를 읽어야 한다.
        if (criterion.equals("latest_purchase")) {
            Comparator<Instant> dates = direction.equals("desc") ? Comparator.reverseOrder() : Comparator.naturalOrder();
            owned = owned.stream().sorted(Comparator.comparing(Owned::lastBuy, Comparator.nullsLast(dates))
                    .thenComparing(Owned::code)).limit(count).toList();
        }
        var evaluated = new ArrayList<Evaluated>();
        for (Owned row : owned) {
            long price;
            try {
                price = market.currentPrice(row.code());
            } catch (IllegalStateException error) {
                throw unavailable(accountId, row.code(), "current_price", error);
            }
            if (row.quantity() <= 0 || row.cost().signum() <= 0 || price <= 0) {
                log.error("보유조회 유효하지 않은 평가 입력 accountId={} stockCode={}", accountId, row.code());
                throw new AccountException(HttpStatus.INTERNAL_SERVER_ERROR, "INVALID_HOLDINGS_DATA", "보유 데이터를 확인해주세요.");
            }
            // 평균단가를 반올림해 원가를 역산하지 않는다. 손익은 저장된 총 취득원가로 계산한다.
            long value = Math.multiplyExact(price, row.quantity());
            BigDecimal pnl = BigDecimal.valueOf(value).subtract(row.cost());
            BigDecimal rate = pnl.multiply(BigDecimal.valueOf(100)).divide(row.cost(), MathContext.DECIMAL128);
            evaluated.add(new Evaluated(row, price, value, pnl, rate));
            log.debug("보유조회 평가 완료 accountId={} stockCode={}", accountId, row.code());
        }

        // 3. 평가 정렬은 응답 반올림 전 값으로 정렬하고 동률은 종목코드 오름차순으로 고정한다.
        if (!criterion.equals("latest_purchase")) {
            Comparator<Evaluated> comparator = criterion.equals("market_value")
                    ? Comparator.comparingLong(Evaluated::value) : Comparator.comparing(Evaluated::rate);
            if (direction.equals("desc")) comparator = comparator.reversed();
            evaluated.sort(comparator.thenComparing(e -> e.owned().code()));
        }
        var items = new ArrayList<Item>();
        for (var result : evaluated.stream().limit(count).toList()) {
            var row = result.owned();
            KiwoomMarketClient.StockDetails detail;
            try {
                detail = market.stockDetails(row.code());
            } catch (IllegalStateException error) {
                throw unavailable(accountId, row.code(), "stock_details", error);
            }
            // 4. 키움 업종 공백만 승인된 표시값으로 대체한다. 시세 실패에는 더미를 넣지 않는다.
            String sector = detail.sector().isBlank() ? "미분류" : detail.sector();
            if (detail.sector().isBlank()) log.warn("보유조회 업종 대체 accountId={} stockCode={} field=sector", accountId, row.code());
            items.add(new Item(row.code(), detail.stockName(), sector, row.quantity(), row.cost(),
                    row.cost().divide(BigDecimal.valueOf(row.quantity()), MathContext.DECIMAL128).setScale(2, RoundingMode.HALF_UP),
                    result.price(), result.value(), result.pnl().setScale(2, RoundingMode.HALF_UP),
                    result.rate().setScale(4, RoundingMode.HALF_UP)));
        }
        log.info("보유조회 완료 accountId={} count={} elapsedMs={}", accountId, items.size(),
                (System.nanoTime() - started) / 1_000_000);
        return new AccountHoldingsResponse("success", accountId, List.copyOf(items));
    }

    private int parseLimit(String value) {
        if (value == null) return Integer.MAX_VALUE;
        try {
            if (!value.matches("[0-9]+")) throw invalidQuery();
            int limit = Integer.parseInt(value);
            if (limit <= 0) throw invalidQuery();
            return limit;
        } catch (NumberFormatException error) {
            throw invalidQuery();
        }
    }

    private AccountException invalidQuery() {
        return new AccountException(HttpStatus.BAD_REQUEST, "INVALID_HOLDINGS_QUERY", "sort, order, limit 값을 확인해주세요.");
    }

    private AccountException unavailable(long accountId, String code, String stage, IllegalStateException error) {
        // 공급자 원문은 인증정보를 포함할 수 있어 출력하지 않는다. 단계와 예외 종류로 위치를 추적한다.
        log.error("보유조회 외부 데이터 실패 accountId={} stockCode={} stage={} causeType={}",
                accountId, code, stage, error.getClass().getSimpleName());
        return new AccountException(HttpStatus.SERVICE_UNAVAILABLE, "HOLDINGS_DATA_UNAVAILABLE", "보유 종목 정보를 조회할 수 없습니다.");
    }

    private record Owned(String code, long quantity, BigDecimal cost, Instant lastBuy) {}
    private record Evaluated(Owned owned, long price, long value, BigDecimal pnl, BigDecimal rate) {}
}
