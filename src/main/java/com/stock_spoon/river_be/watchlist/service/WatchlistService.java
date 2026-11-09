package com.stock_spoon.river_be.watchlist.service;

import com.stock_spoon.river_be.user.entity.User;
import com.stock_spoon.river_be.watchlist.dto.WatchlistResponse;
import com.stock_spoon.river_be.watchlist.entity.Watchlist;
import com.stock_spoon.river_be.watchlist.entity.WatchlistId;
import com.stock_spoon.river_be.watchlist.repository.StockRepository;
import com.stock_spoon.river_be.watchlist.repository.WatchlistRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class WatchlistService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(WatchlistService.class);
    private final WatchlistRepository watchlists;
    private final StockRepository stocks;
    private final EntityManager entityManager;
    private final StockCatalogService catalog;
    private final TransactionTemplate read;
    private final TransactionTemplate write;

    public WatchlistService(WatchlistRepository watchlists, StockRepository stocks, EntityManager entityManager,
            StockCatalogService catalog, PlatformTransactionManager manager) {
        this.watchlists = watchlists;
        this.stocks = stocks;
        this.entityManager = entityManager;
        this.catalog = catalog;
        read = new TransactionTemplate(manager);
        read.setReadOnly(true);
        write = new TransactionTemplate(manager);
    }

    /** 사용자 존재를 확인하고, 모든 계좌에서 공유하는 관심 목록을 최근 등록순으로 반환한다. */
    @Transactional(readOnly = true)
    public WatchlistResponse list(long userId) {
        requireUser(userId, false);
        var items = watchlists.list(userId).stream().map(row -> {
            var stock = row.getStock();
            return new WatchlistResponse.Item(stock.getStockCode(), stock.getName(), stock.getSector());
        }).toList();
        return new WatchlistResponse("success", items);
    }

    /** 종목을 확인해 관심 목록에 등록한다. 신규 등록은 true, 기존 등록은 false를 반환한다. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public boolean add(long userId, String code) {
        validate(code);
        read.executeWithoutResult(status -> requireUser(userId, false));
        catalog.resolveStockId(code);
        return write.execute(status -> addResolved(userId, code));
    }

    private boolean addResolved(long userId, String code) {
        requireUser(userId, true);
        var matches = stocks.findAllByStockCode(code);
        if (matches.isEmpty()) {
            log.warn("event=watchlist_add_rejected stage=resolved_stock_lookup userId={} stockCode={} code=STOCK_NOT_FOUND", userId, code);
            throw new WatchlistException(HttpStatus.NOT_FOUND, "STOCK_NOT_FOUND", "종목을 찾을 수 없습니다.");
        }
        if (matches.size() != 1) {
            log.warn("event=watchlist_add_rejected stage=resolved_stock_lookup userId={} stockCode={} code=AMBIGUOUS_STOCK_CODE", userId, code);
            throw new WatchlistException(HttpStatus.CONFLICT, "AMBIGUOUS_STOCK_CODE", "종목코드가 여러 시장에 존재합니다.");
        }
        var stock = matches.getFirst();
        if (watchlists.existsById(new WatchlistId(userId, stock.getId()))) return false;
        entityManager.persist(new Watchlist(userId, stock));
        return true;
    }

    /** 해당 사용자의 관심 등록만 해제한다. 이미 해제된 종목도 정상 처리한다. */
    @Transactional
    public void remove(long userId, String code) {
        validate(code);
        requireUser(userId, true);
        watchlists.remove(userId, code);
    }

    /** 사용자를 확인하고, 변경 요청은 사용자 행을 잠가 같은 사용자의 동시 변경을 직렬화한다. */
    private void requireUser(long userId, boolean lock) {
        var user = lock ? entityManager.find(User.class, userId, LockModeType.PESSIMISTIC_WRITE)
                : entityManager.find(User.class, userId);
        if (user == null) {
            log.warn("event=watchlist_request_rejected stage=user_lookup userId={} code=USER_NOT_FOUND", userId);
            throw new WatchlistException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "로그인이 필요합니다.");
        }
    }

    /** 내부 호출에서도 숫자·영문 대문자 6자리 종목코드만 허용한다. */
    private void validate(String code) {
        if (code == null || !code.matches("[0-9A-Z]{6}")) {
            log.warn("event=watchlist_request_rejected stage=stock_code_validation code=INVALID_STOCK_CODE");
            throw new WatchlistException(HttpStatus.BAD_REQUEST, "INVALID_STOCK_CODE", "종목코드를 확인해주세요.");
        }
    }
}
