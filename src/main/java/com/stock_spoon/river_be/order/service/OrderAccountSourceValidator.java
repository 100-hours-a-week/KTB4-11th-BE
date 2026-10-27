package com.stock_spoon.river_be.order.service;

import com.stock_spoon.river_be.account.entity.Account;
import com.stock_spoon.river_be.order.entity.Order;
import com.stock_spoon.river_be.order.exception.OrderException;
import java.time.Instant;
import org.springframework.http.HttpStatus;

/** 접수 사전 검사와 잠금 후 검사가 동일한 계좌 유형·이전 버전 안내를 사용한다. */
public final class OrderAccountSourceValidator {
    // 2026-10-20 22:00 Asia/Seoul. 정확히 이 시각에 생성된 계좌는 제외한다.
    private static final Instant LEGACY_CUTOFF = Instant.parse("2026-10-20T13:00:00Z");

    private OrderAccountSourceValidator() {}

    public static void assertAllowed(Account account, Order.Source source) {
        if (source != null && account.isAiManaged() == (source == Order.Source.AI)) return;
        if (source == Order.Source.USER && account.isAiManaged()
                && account.getCreatedAt() != null && account.getCreatedAt().isBefore(LEGACY_CUTOFF)) {
            throw new OrderException(HttpStatus.FORBIDDEN, "LEGACY_ACCOUNT_USER_ORDER_NOT_ALLOWED",
                    "이전 버전에서 생성된 계좌는 사용자 직접 주문을 지원하지 않습니다. 새 계좌를 생성해 주세요.");
        }
        throw new OrderException(HttpStatus.FORBIDDEN, "FORBIDDEN_ACCOUNT",
                "주문 주체와 계좌 운용 유형이 일치하지 않습니다.");
    }
}
