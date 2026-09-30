package com.stock_spoon.river_be.order;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HoldingTests {
    @Test
    void rejectsZeroCostIncludingRoundingAndPreservesHoldingOnInvalidReduction() {
        assertThrows(IllegalArgumentException.class,
                () -> new Holding(null, "005930", 1, BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> new Holding(null, "005930", 1, new BigDecimal("0.004")));
        var holding = new Holding(null, "005930", 2, new BigDecimal("0.01"));
        assertThrows(IllegalArgumentException.class,
                () -> holding.reduce(1, new BigDecimal("0.009")));
        assertEquals(2, holding.getQuantity());
        assertEquals(new BigDecimal("0.01"), holding.getTotalCost());
    }
}