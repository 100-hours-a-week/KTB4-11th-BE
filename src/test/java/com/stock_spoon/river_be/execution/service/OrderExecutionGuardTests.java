package com.stock_spoon.river_be.execution.service;

import com.stock_spoon.river_be.order.dto.OrderCreateRequest;
import com.stock_spoon.river_be.order.entity.Order;
import com.stock_spoon.river_be.execution.repository.ExecutionRepository;
import com.stock_spoon.river_be.holding.repository.HoldingRepository;
import com.stock_spoon.river_be.order.repository.OrderRepository;

import com.stock_spoon.river_be.account.entity.Account;
import com.stock_spoon.river_be.account.repository.AccountRepository;
import com.stock_spoon.river_be.market.kiwoom.KiwoomStockStream;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class OrderExecutionGuardTests {
    @Test
    void reachingCloseDuringValidationKeepsPendingAndDoesNotChangeAssets() {
        var accounts = mock(AccountRepository.class);
        var orders = mock(OrderRepository.class);
        var holdings = mock(HoldingRepository.class);
        var executions = mock(ExecutionRepository.class);
        var account = mock(Account.class);
        var pending = mock(Order.class);
        var clock = mock(Clock.class);
        var now = new java.util.concurrent.atomic.AtomicReference<>(Instant.parse("2026-10-19T06:29:59Z"));
        when(clock.instant()).thenAnswer(ignored -> now.get());
        var service = new OrderExecutionService(accounts, orders, holdings, executions, clock);
        when(accounts.findLockedById(1L)).thenReturn(Optional.of(account));
        when(orders.findByIdAndAccountId(2L, 1L)).thenReturn(Optional.of(pending));
        when(pending.getStatus()).thenReturn(Order.Status.PENDING);
        when(pending.getType()).thenReturn(Order.Type.LIMIT);
        when(pending.getSide()).thenReturn(Order.Side.BUY);
        when(pending.getLimitPrice()).thenReturn(70000L);
        when(pending.getQuantity()).thenReturn(1L);
        assertThat(service.executeLimit(1L, 2L, 69000, Map.of(),
                () -> now.set(Instant.parse("2026-10-19T06:30:00Z")))).isFalse();
        verify(account, never()).changeCash(anyLong());
        verify(pending, never()).cancel(any());
        verify(pending, never()).execute();
        verifyNoInteractions(executions);
    }

    @Test
    void sessionLostWhileAcquiringAccountLockStopsLimitAndMarketAssets() {
        var accounts = mock(AccountRepository.class);
        var orders = mock(OrderRepository.class);
        var holdings = mock(HoldingRepository.class);
        var executions = mock(ExecutionRepository.class);
        var account = mock(Account.class);
        var pending = mock(Order.class);
        var valid = new AtomicBoolean(true);
        Instant now = Instant.parse("2026-10-01T01:00:00Z");
        var service = new OrderExecutionService(accounts, orders, holdings, executions, Clock.fixed(now, ZoneOffset.UTC));
        when(accounts.findLockedById(1L)).thenAnswer(call -> { valid.set(false); return Optional.of(account); });
        when(orders.findByIdAndAccountId(2L, 1L)).thenReturn(Optional.of(pending));
        when(pending.getStatus()).thenReturn(Order.Status.PENDING);
        when(pending.getType()).thenReturn(Order.Type.LIMIT);
        when(pending.getCreatedAt()).thenReturn(now);
        when(pending.getSide()).thenReturn(Order.Side.BUY);
        when(pending.getLimitPrice()).thenReturn(70000L);
        when(pending.getQuantity()).thenReturn(1L);
        when(pending.getStockCode()).thenReturn("005930");
        when(account.belongsTo(1L)).thenReturn(true);
        when(account.isActive()).thenReturn(true);
        when(account.isAiManaged()).thenReturn(true);
        when(account.getCashBalance()).thenReturn(1000000L);
        Runnable validate = () -> { if (!valid.get()) throw new IllegalStateException("previous session"); };
        validate.run();
        assertThatThrownBy(() -> service.executeLimit(1L, 2L, 69000L, Map.of(), validate))
                .isInstanceOf(IllegalStateException.class).hasMessage("previous session");
        var request = new OrderCreateRequest("005930", "buy", "market", null, 1L, "근거", null, List.of(), 100.0, null);
        var book = new KiwoomStockStream.OrderBook("005930",
                List.of(new KiwoomStockStream.QuoteLevel(BigDecimal.valueOf(70000), 1)), List.of(), LocalTime.of(10, 0), now);
        valid.set(true);
        validate.run();
        assertThatThrownBy(() -> service.executeMarket(1L, 1L, request, book, Map.of(), validate))
                .isInstanceOf(IllegalStateException.class).hasMessage("previous session");
        verify(account, never()).changeCash(anyLong());
        verify(pending, never()).execute();
        verify(orders, never()).save(any());
        verify(holdings, never()).save(any());
        verify(executions, never()).save(any());
    }
}
