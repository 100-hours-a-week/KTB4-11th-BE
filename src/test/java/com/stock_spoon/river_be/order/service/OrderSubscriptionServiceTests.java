package com.stock_spoon.river_be.order.service;

import com.stock_spoon.river_be.order.entity.Order;
import com.stock_spoon.river_be.order.exception.OrderException;
import com.stock_spoon.river_be.order.repository.OrderRepository;

import com.stock_spoon.river_be.market.kiwoom.KiwoomStockStream;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class OrderSubscriptionServiceTests {
    private final OrderRepository orders = mock(OrderRepository.class);
    private final KiwoomStockStream stream = mock(KiwoomStockStream.class);
    private final OrderService orderService = mock(OrderService.class);
    private final OrderSubscriptionService service = new OrderSubscriptionService(orders, stream, orderService);

    @Test
    void requestDemandHasUniqueIdAndIsCheckedBeforeSave() {
        when(orders.findStockCodesByStatus(Order.Status.PENDING)).thenReturn(Set.of());
        when(stream.whenSubscribed("005930")).thenReturn(CompletableFuture.completedFuture(null));
        service.create("005930", () -> null);
        var id = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(stream).retainRequest(id.capture(), eq("005930"));
        assertThat(id.getValue()).matches("[0-9a-f-]{36}");
        verify(stream).assertRequest(id.getValue(), "005930");
        verify(stream).assertAvailable("005930");
        verify(stream).releaseRequest(id.getValue(), "005930");
    }

    @Test
    void redisDemandFailureReturnsUnavailableWithoutSaving() {
        doThrow(new IllegalStateException("Redis down")).when(stream).retainRequest(anyString(), eq("005930"));
        assertThatThrownBy(() -> service.create("005930", () -> { throw new AssertionError("저장 금지"); }))
                .isInstanceOfSatisfying(OrderException.class, error -> assertThat(error.status().value()).isEqualTo(503));
    }

    @Test
    void waitsForRegistrationBeforeSavingAndRetainsCommittedPendingOrder() throws Exception {
        when(orders.findStockCodesByStatus(Order.Status.PENDING)).thenReturn(Set.of());
        var registered = new CompletableFuture<Void>();
        var requested = new CompletableFuture<Void>();
        when(stream.whenSubscribed("005930")).thenAnswer(ignored -> {
            requested.complete(null);
            return registered;
        });
        var saved = new CompletableFuture<Void>();
        try (var executor = Executors.newSingleThreadExecutor()) {
            var result = executor.submit(() -> service.create("005930", () -> {
                when(orders.findStockCodesByStatus(Order.Status.PENDING)).thenReturn(Set.of("005930"));
                saved.complete(null);
                return null;
            }));
            requested.get(2, TimeUnit.SECONDS);
            assertThat(saved).isNotDone();
            registered.complete(null);
            result.get(2, TimeUnit.SECONDS);
            assertThat(saved).isCompleted();
            verify(stream, never()).updateSymbols(Set.of());
        }
    }

    @Test
    void timeoutOrRejectionNeverSavesAndReleasesOnlyTemporaryDemand() {
        when(orders.findStockCodesByStatus(Order.Status.PENDING)).thenReturn(Set.of("000660"));
        for (CompletableFuture<Void> registration : java.util.List.<CompletableFuture<Void>>of(
                new CompletableFuture<>(), CompletableFuture.failedFuture(new IllegalStateException()))) {
            when(stream.whenSubscribed("005930")).thenReturn(registration);
            assertThatThrownBy(() -> service.create("005930", () -> {
                throw new AssertionError("구독 실패 후 저장하면 안 됩니다.");
            }, Duration.ZERO)).isInstanceOfSatisfying(OrderException.class,
                    error -> assertThat(error.status().value()).isEqualTo(503));
        }
        verify(stream, times(2)).updateSymbols(Set.of("000660"));
    }

    @Test
    void databaseFailureDoesNotLeaveTemporarySubscription() {
        when(orders.findStockCodesByStatus(Order.Status.PENDING)).thenReturn(Set.of());
        when(stream.whenSubscribed("005930")).thenReturn(CompletableFuture.completedFuture(null));
        assertThatThrownBy(() -> service.create("005930", () -> {
            throw new OrderException("잔액 부족");
        })).isInstanceOf(OrderException.class).hasMessage("잔액 부족");
        verify(stream).updateSymbols(Set.of());
    }

    @Test
    void refreshRestoresPendingSymbolsAndRemovesOnlyAfterLastOrderDisappears() {
        when(orders.findStockCodesByStatus(Order.Status.PENDING))
                .thenReturn(Set.of("005930", "000660"), Set.of("005930", "000660"), Set.of("000660"));
        service.refresh();
        service.refresh();
        service.refresh();
        verify(stream, times(2)).updateSymbols(Set.of("005930", "000660"));
        verify(stream).updateSymbols(Set.of("000660"));
        verify(orderService, times(3)).cancelExpiredPendingOrders();
    }
}
