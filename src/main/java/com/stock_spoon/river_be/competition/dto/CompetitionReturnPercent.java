package com.stock_spoon.river_be.competition.dto;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** 응답 수익률만 한 번 반올림한다. 저장값과 순위 비교 정밀도는 변경하지 않는다. */
public final class CompetitionReturnPercent {
    private CompetitionReturnPercent() {}
    public static BigDecimal fromAssets(long assets, long initialCapital) {
        return BigDecimal.valueOf(assets).subtract(BigDecimal.valueOf(initialCapital))
                .multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(initialCapital), 1, RoundingMode.HALF_UP);
    }
}
