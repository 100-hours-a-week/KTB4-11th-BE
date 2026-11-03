package com.stock_spoon.river_be.competition;

import com.stock_spoon.river_be.account.repository.AccountRepository;
import com.stock_spoon.river_be.competition.service.CompetitionService;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class CompetitionNameAvailabilityTests {
    private final AccountRepository accounts = mock(AccountRepository.class);
    private final CompetitionService service = new CompetitionService(null, accounts, null, null, null, null, null);

    @Test
    void validNameIsCheckedAgainstTheUsersAccountsAfterTrimming() {
        when(accounts.existsByUserIdAndNameIgnoreCase(1L, "반도체"))
                .thenReturn(false, true);

        assertThat(service.checkNameAvailability(1L, " 반도체 ").isAvailable()).isTrue();
        assertThat(service.checkNameAvailability(1L, " 반도체 ").isAvailable()).isFalse();
        verify(accounts, times(2)).existsByUserIdAndNameIgnoreCase(1L, "반도체");
    }

    @Test
    void emptyAndOverlongNamesAreNotAvailable() {
        assertThat(service.checkNameAvailability(1L, null).isAvailable()).isFalse();
        assertThat(service.checkNameAvailability(1L, "  ").isAvailable()).isFalse();
        assertThat(service.checkNameAvailability(1L, "가".repeat(21)).isAvailable()).isFalse();
        assertThat(service.checkNameAvailability(1L, " " + "가".repeat(20)).isAvailable()).isFalse();
        verifyNoInteractions(accounts);
    }
}
