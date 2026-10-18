package com.stock_spoon.river_be.order.validator;

import com.stock_spoon.river_be.order.exception.OrderException;

import org.springframework.stereotype.Component;

/** 코스피200 일반 주식 주문의 지정가 호가단위를 확인한다. */
@Component
public class OrderMarketValidator {
    public void validateLimit(long limitPrice) {
        long tick = tickSize(limitPrice);
        if (limitPrice <= 0 || limitPrice % tick != 0) {
            throw new OrderException("지정가가 호가가격단위에 맞지 않습니다.");
        }
    }

    static long tickSize(long price) {
        if (price < 2_000) return 1;
        if (price < 5_000) return 5;
        if (price < 20_000) return 10;
        if (price < 50_000) return 50;
        if (price < 200_000) return 100;
        if (price < 500_000) return 500;
        return 1_000;
    }
}
