package com.stock_spoon.river_be.market;

import java.math.BigDecimal;

public record QuoteLevel(BigDecimal price, long quantity) {}
