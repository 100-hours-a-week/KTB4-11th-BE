package com.stock_spoon.river_be.competition.service;

import com.stock_spoon.river_be.account.entity.Account;
import com.stock_spoon.river_be.account.repository.AccountRepository;
import com.stock_spoon.river_be.competition.dto.CompetitionAgentRequest;
import com.stock_spoon.river_be.competition.entity.*;
import com.stock_spoon.river_be.competition.exception.CompetitionException;
import com.stock_spoon.river_be.competition.logging.CompetitionCreationLog;
import com.stock_spoon.river_be.competition.repository.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CompetitionAgentCreationService {
    private final CompetitionRepository competitions;
    private final CompetitionParticipantRepository participants;
    private final CompetitionAgentRepository agents;
    private final AccountRepository accounts;

    @Transactional(propagation = Propagation.MANDATORY)
    public void createParticipants(Competition competition, List<CompetitionAgentRequest> inputs) {
        for (int i = 0; i < inputs.size(); i++) {
            CompetitionCreationLog.stage("validate_agent_themes");
            var input = inputs.get(i);
            var preferred = normalizedThemes(input.preferredThemes());
            var excluded = normalizedThemes(input.excludedThemes());
            var includedKeys = new HashSet<>(preferred.stream().map(s -> s.toLowerCase(Locale.ROOT)).toList());
            if (excluded.stream().map(s -> s.toLowerCase(Locale.ROOT)).anyMatch(includedKeys::contains)) {
                throw invalidThemes("같은 테마를 관심·제외에 동시에 지정할 수 없습니다.");
            }
            CompetitionCreationLog.stage("ai_account_save");
            var account = accounts.save(new Account(competition.getUser(),
                    nextAccountName(competition.getUser().getId(), competition.getId(), i),
                    competition.getInitialCapital(), true));
            CompetitionCreationLog.accountId(account.getId());
            CompetitionCreationLog.stage("ai_participant_save");
            var participant = participants.save(new CompetitionParticipant(competition, account));
            CompetitionCreationLog.stage("ai_strategy_save");
            agents.save(new CompetitionAgent(participant, i, input.riskLevel(), preferred, excluded,
                    input.additionalRequest() == null ? "" : input.additionalRequest().trim()));
        }
        CompetitionCreationLog.accountId(null);
    }

    private List<String> normalizedThemes(List<String> values) {
        var keys = new HashSet<String>();
        var result = new ArrayList<String>();
        for (var value : values) {
            String theme = value.trim();
            if (theme.isBlank() || !keys.add(theme.toLowerCase(Locale.ROOT))) {
                throw invalidThemes("테마는 공백 없이 중복되지 않게 지정해주세요.");
            }
            result.add(theme);
        }
        return result;
    }

    private String nextAccountName(Long userId, Long competitionId, int position) {
        String base = "AI-" + Long.toString(competitionId, 36) + "-" + (position + 1);
        String name = base;
        for (int suffix = 1; accounts.existsByUserIdAndNameIgnoreCase(userId, name)
                || competitions.existsByUserIdAndCompetitionNameIgnoreCase(userId, name); suffix++) {
            String tail = "-" + Integer.toString(suffix, 36);
            name = base.substring(0, Math.min(base.length(), 20 - tail.length())) + tail;
        }
        return name;
    }

    private CompetitionException invalidThemes(String message) {
        return new CompetitionException(HttpStatus.BAD_REQUEST, "INVALID_AGENT_THEMES", message);
    }
}
