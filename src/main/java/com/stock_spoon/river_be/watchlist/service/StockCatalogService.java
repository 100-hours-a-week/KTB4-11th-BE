package com.stock_spoon.river_be.watchlist.service;

import com.stock_spoon.river_be.market.kiwoom.KiwoomMarketClient;
import com.stock_spoon.river_be.watchlist.entity.Stock;
import com.stock_spoon.river_be.watchlist.repository.StockRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClientException;

@Service
public class StockCatalogService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(StockCatalogService.class);
    private final StockRepository stocks;
    private final KiwoomMarketClient market;
    private final TransactionTemplate read;
    private final TransactionTemplate write;

    public StockCatalogService(StockRepository stocks, KiwoomMarketClient market, PlatformTransactionManager manager) {
        this.stocks = stocks;
        this.market = market;
        read = new TransactionTemplate(manager);
        read.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        read.setReadOnly(true);
        write = new TransactionTemplate(manager);
        write.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** 외부 조회 중에는 호출자가 가진 트랜잭션도 중단한다. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public long resolveStockId(String code) {
        if (code == null || !code.matches("[0-9A-Z]{6}")) {
            log.warn("event=watchlist_stock_resolution_failed stage=stock_code_validation code=INVALID_STOCK_CODE");
            throw new WatchlistException(HttpStatus.BAD_REQUEST, "INVALID_STOCK_CODE", "종목코드를 확인해주세요.");
        }
        Long existing = read.execute(status -> existingId(code));
        if (existing != null) return resolved(code, existing, "database");
        KiwoomMarketClient.StockMaster master;
        try {
            master = market.stockMaster(code);
        } catch (IllegalStateException | RestClientException error) {
            log.warn("event=watchlist_stock_resolution_failed stage=kiwoom code=STOCK_INFO_UNAVAILABLE stockCode={} causeType={} nestedCauseType={}",
                    code, error.getClass().getSimpleName(),
                    error.getCause() == null ? "none" : error.getCause().getClass().getSimpleName());
            throw new WatchlistException(HttpStatus.SERVICE_UNAVAILABLE, "STOCK_INFO_UNAVAILABLE", "종목정보를 가져올 수 없습니다.");
        }
        long id;
        try {
            id = write.execute(status -> {
                Long found = existingId(code);
                if (found != null) return found;
                return stocks.saveAndFlush(new Stock(master.stockCode(), master.stockName(), master.marketCode(), master.sector())).getId();
            });
        } catch (DataIntegrityViolationException error) {
            // 실패한 쓰기 트랜잭션의 롤백이 끝난 뒤 실제 중복 행을 확인한다.
            Long winner = read.execute(status -> {
                if (stocks.findByMarketAndStockCode(master.marketCode(), code).isEmpty()) return null;
                return existingId(code);
            });
            if (winner == null) {
                log.error("event=watchlist_stock_resolution_failed stage=stock_insert_conflict_recheck stockCode={} code=UNRECOVERED_INTEGRITY_VIOLATION causeType={} nestedCauseType={}",
                        code, error.getClass().getSimpleName(),
                        error.getCause() == null ? "none" : error.getCause().getClass().getSimpleName());
                throw error;
            }
            id = winner;
        }
        return resolved(code, id, "kiwoom");
    }

    private Long existingId(String code) {
        var matches = stocks.findAllByStockCode(code);
        if (matches.size() > 1) {
            log.warn("event=watchlist_stock_resolution_failed stage=existing_stock_lookup stockCode={} code=AMBIGUOUS_STOCK_CODE", code);
            throw new WatchlistException(HttpStatus.CONFLICT, "AMBIGUOUS_STOCK_CODE", "종목코드가 여러 시장에 존재합니다.");
        }
        return matches.isEmpty() ? null : matches.getFirst().getId();
    }

    private long resolved(String code, long id, String source) {
        log.info("event=watchlist_stock_resolved source={} stockCode={} stockId={}", source, code, id);
        return id;
    }
}
