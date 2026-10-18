package com.stock_spoon.river_be.order.validator;

import com.stock_spoon.river_be.order.exception.OrderException;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class OrderMarketValidatorTests {
    private final OrderMarketValidator validator = new OrderMarketValidator();

    @Test
    void acceptsOrdinaryStockTicksWithoutExternalMarketInformation() {
        for (long price : new long[]{1, 1999, 2000, 4995, 5000, 19990, 20000, 49950,
                50000, 199900, 200000, 499500, 500000, 1000000}) {
            assertThatCode(() -> validator.validateLimit(price)).doesNotThrowAnyException();
        }
    }

    @Test
    void stillRejectsNonPositivePricesAndInvalidTicks() {
        for (long price : new long[]{0, -1, 2001, 5005, 20010, 50050, 70050, 200100, 500500}) {
            assertThatThrownBy(() -> validator.validateLimit(price))
                    .isInstanceOf(OrderException.class);
        }
    }
}
