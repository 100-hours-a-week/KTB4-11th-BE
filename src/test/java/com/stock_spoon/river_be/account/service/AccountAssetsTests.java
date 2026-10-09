package com.stock_spoon.river_be.account.service;

import com.stock_spoon.river_be.account.entity.Account;
import com.stock_spoon.river_be.account.repository.AccountRepository;
import com.stock_spoon.river_be.account.exception.AccountException;
import com.stock_spoon.river_be.market.kiwoom.KiwoomMarketClient;
import com.stock_spoon.river_be.order.*;
import com.stock_spoon.river_be.user.entity.User;
import com.stock_spoon.river_be.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@Transactional
class AccountAssetsTests {
    @Autowired AccountService service;
    @Autowired AccountRepository accounts;
    @Autowired UserRepository users;
    @Autowired HoldingRepository holdings;
    @Autowired OrderRepository orders;
    @Autowired ExecutionRepository executions;
    @MockitoBean KiwoomMarketClient market;

    @Test void evaluatesEveryHoldingAndSeparatesAccounts() {
        var user = users.save(new User("평가 테스트"));
        var first = accounts.save(new Account(user, "첫 계좌", 1000000));
        var second = accounts.save(new Account(user, "둘째 계좌", 1000000));
        first.changeCash(-300000);
        second.changeCash(-100000);
        holdings.save(new Holding(first, "005930", 2, new BigDecimal("200000")));
        holdings.save(new Holding(first, "000660", 1, new BigDecimal("100000")));
        holdings.save(new Holding(second, "005930", 1, new BigDecimal("100000")));
        when(market.currentPrice("005930")).thenReturn(120000L);
        when(market.currentPrice("000660")).thenReturn(90000L);
        var result = service.list(user.getId());
        assertThat(result).extracting(r -> r.totalAssets()).containsExactly(1030000L, 1020000L);
        assertThat(result).extracting(r -> r.returnPercent()).containsExactly(3.0, 2.0);
        verify(market, times(1)).currentPrice("005930");
        var detail = service.get(user.getId(), first.getId());
        assertThat(detail.holdingsMarketValue()).isEqualTo(330000);
        assertThat(detail.totalAssets()).isEqualTo(1030000);
        assertThat(detail.returnPercent()).isEqualTo(3.0);
        assertThat(detail.cashBalance()).isEqualTo(700000);
    }

    @Test void emptyHoldingsNeedNoMarketCallAndLossIsNegative() {
        var user = users.save(new User("빈 계좌"));
        assertThat(service.list(user.getId())).isEmpty();
        var account = accounts.save(new Account(user, "계좌", 1000000));
        account.changeCash(-123456);
        var detail = service.get(user.getId(), account.getId());
        assertThat(detail.executedTradeCount()).isZero();
        assertThat(detail.holdingsMarketValue()).isZero();
        assertThat(detail.totalAssets()).isEqualTo(876544);
        assertThat(detail.returnPercent()).isEqualTo(-12.3456);
        verifyNoInteractions(market);
    }

    @Test void unavailablePriceFailsInsteadOfReturningPartialAssets() {
        var user = users.save(new User("실패 계좌"));
        var account = accounts.save(new Account(user, "계좌", 1000000));
        holdings.save(new Holding(account, "005930", 1, new BigDecimal("100000")));
        when(market.currentPrice("005930")).thenThrow(new IllegalStateException("unavailable"));
        assertThatThrownBy(() -> service.get(user.getId(), account.getId()))
                .isInstanceOfSatisfying(AccountException.class, error -> { assertThat(error.status()).isEqualTo(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE); assertThat(error.code()).isEqualTo("HOLDINGS_DATA_UNAVAILABLE"); });
        assertThatThrownBy(() -> service.list(user.getId())).isInstanceOfSatisfying(AccountException.class, error -> { assertThat(error.status()).isEqualTo(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE); assertThat(error.code()).isEqualTo("HOLDINGS_DATA_UNAVAILABLE"); });
    }
    @Test void countsCompletedOrdersAcrossSidesAndSourcesButNotFillsOrOtherAccounts() {
        var user = users.save(new User("거래 집계"));
        var account = accounts.save(new Account(user, "집계 계좌", 1000000));
        var other = accounts.save(new Account(user, "다른 계좌", 1000000));
        var now = java.time.Instant.parse("2026-10-09T00:00:00Z");
        var buy = orders.save(Order.pendingLimit(account, "005930", Order.Side.BUY, 2, 100,
                Order.Source.AI, "매수 근거", now));
        buy.execute();
        executions.save(new Execution(buy, 100, 1, null, null, now));
        executions.save(new Execution(buy, 100, 1, null, null, now.plusSeconds(1)));
        var sell = orders.save(Order.pendingLimit(account, "005930", Order.Side.SELL, 1, 100,
                Order.Source.USER, null, now));
        sell.execute();
        executions.save(new Execution(sell, 100, 1, BigDecimal.ZERO, BigDecimal.ZERO, now));
        orders.save(Order.pendingLimit(account, "005930", Order.Side.BUY, 1, 100,
                Order.Source.AI, "대기 근거", now));
        var cancelled = orders.save(Order.pendingLimit(account, "005930", Order.Side.BUY, 1, 100,
                Order.Source.USER, null, now));
        cancelled.cancel(now);
        var otherOrder = orders.save(Order.pendingLimit(other, "005930", Order.Side.BUY, 1, 100,
                Order.Source.USER, null, now));
        otherOrder.execute();
        assertThat(service.get(user.getId(), account.getId()).executedTradeCount()).isEqualTo(2);
        assertThat(service.get(user.getId(), other.getId()).executedTradeCount()).isEqualTo(1);
        verifyNoInteractions(market);
    }
    @Test void foreignAccountCannotTriggerMarketLookup() {
        var owner = users.save(new User("소유자"));
        var other = users.save(new User("다른 사용자"));
        var account = accounts.save(new Account(owner, "계좌", 1000000));
        holdings.save(new Holding(account, "005930", 1, new BigDecimal("100000")));
        assertThatThrownBy(() -> service.get(other.getId(), account.getId()))
                .isInstanceOfSatisfying(AccountException.class,
                        error -> assertThat(error.status()).isEqualTo(org.springframework.http.HttpStatus.NOT_FOUND));
        assertThat(service.list(other.getId())).isEmpty();
        verifyNoInteractions(market);
    }
}