package com.stock_spoon.river_be.market;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;

class DisabledMarketStreamTests {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(DisabledMarketStream.class);

    @Test
    void disabledSubscriptionHasNoUsableQuote() {
        context.run(app -> {
            var stream = app.getBean(MarketStream.class);
            stream.updateSymbols(Set.of("005930"));
            assertThat(stream.latest("005930")).isEmpty();
            assertThat(stream.latestOrderBook("005930")).isEmpty();
            assertThatThrownBy(() -> stream.assertAvailable("005930"))
                    .isInstanceOf(IllegalStateException.class);
        });
    }

    @Test
    void enabledRedisSubscriptionDoesNotCreateDisabledStream() {
        context.withPropertyValues("kiwoom.stream.enabled=true")
                .run(app -> assertThat(app).doesNotHaveBean(DisabledMarketStream.class));
    }
}
