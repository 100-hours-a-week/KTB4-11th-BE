package com.stock_spoon.river_be.order.service;

import com.stock_spoon.river_be.account.entity.Account;
import com.stock_spoon.river_be.order.entity.Order;
import com.stock_spoon.river_be.order.exception.OrderException;
import com.stock_spoon.river_be.user.entity.User;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;

class OrderAccountSourceValidatorTests {
    private Account account(boolean ai, String createdAt) {
        var account = new Account(new User("테스트"), "계좌", 100000, ai);
        ReflectionTestUtils.setField(account, "createdAt", Instant.parse(createdAt));
        return account;
    }

    @Test void legacyAiAccountRejectsUserWithSpecificMessage() {
        assertThatThrownBy(() -> OrderAccountSourceValidator.assertAllowed(
                account(true, "2026-10-20T12:59:59.999999Z"), Order.Source.USER))
                .isInstanceOfSatisfying(OrderException.class, error -> {
                    assertThat(error.code()).isEqualTo("LEGACY_ACCOUNT_USER_ORDER_NOT_ALLOWED");
                    assertThat(error.getMessage()).contains("이전 버전", "새 계좌");
                });
    }

    @Test void atAndAfterCutoffKeepExistingMismatchError() {
        for (String timestamp : new String[] {"2026-10-20T13:00:00Z", "2026-10-20T13:00:00.000001Z"}) {
            assertThatThrownBy(() -> OrderAccountSourceValidator.assertAllowed(account(true, timestamp), Order.Source.USER))
                    .isInstanceOfSatisfying(OrderException.class,
                            error -> assertThat(error.code()).isEqualTo("FORBIDDEN_ACCOUNT"));
        }
    }

    @Test void aiMismatchStillUsesExistingErrorEvenBeforeCutoff() {
        assertThatThrownBy(() -> OrderAccountSourceValidator.assertAllowed(
                account(false, "2026-10-20T12:00:00Z"), Order.Source.AI))
                .isInstanceOfSatisfying(OrderException.class,
                        error -> assertThat(error.code()).isEqualTo("FORBIDDEN_ACCOUNT"));
    }

    @Test void matchingAccountTypesRemainAllowedRegardlessOfAge() {
        for (String timestamp : new String[] {"2026-10-20T12:00:00Z", "2026-10-21T00:00:00Z"}) {
            assertThatCode(() -> OrderAccountSourceValidator.assertAllowed(account(false, timestamp), Order.Source.USER))
                    .doesNotThrowAnyException();
            assertThatCode(() -> OrderAccountSourceValidator.assertAllowed(account(true, timestamp), Order.Source.AI))
                    .doesNotThrowAnyException();
        }
    }

    @Test void unknownCreationTimeAndNullActorKeepExistingError() {
        var target = account(true, "2026-10-20T12:00:00Z");
        ReflectionTestUtils.setField(target, "createdAt", null);
        assertThatThrownBy(() -> OrderAccountSourceValidator.assertAllowed(target, Order.Source.USER))
                .isInstanceOfSatisfying(OrderException.class,
                        error -> assertThat(error.code()).isEqualTo("FORBIDDEN_ACCOUNT"));
        assertThatThrownBy(() -> OrderAccountSourceValidator.assertAllowed(target, null))
                .isInstanceOfSatisfying(OrderException.class,
                        error -> assertThat(error.code()).isEqualTo("FORBIDDEN_ACCOUNT"));
    }
}
