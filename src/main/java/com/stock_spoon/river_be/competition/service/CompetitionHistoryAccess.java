package com.stock_spoon.river_be.competition.service;

import com.stock_spoon.river_be.competition.entity.Competition;
import com.stock_spoon.river_be.competition.exception.CompetitionException;
import com.stock_spoon.river_be.competition.repository.CompetitionParticipantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CompetitionHistoryAccess {
    private final CompetitionParticipantRepository participants;

    // 일반 주문·리포트 조회에서도 호출하여 다른 URI로 AI 내역을 미리 읽는 것을 막는다.
    @Transactional(readOnly = true)
    public void check(long userId, long accountId, boolean requireCompetition) {
        var participant = participants.findByAccountId(accountId);
        if (participant.isEmpty()) {
            if (requireCompetition) throw notFound();
            return;
        }
        var link = participant.get();
        if (!link.getCompetition().getUser().getId().equals(userId)) throw notFound();
        if (link.getAccount().isAiManaged() && link.getCompetition().getState() != Competition.State.COMPLETED) {
            throw new CompetitionException(HttpStatus.FORBIDDEN, "COMPETITION_AI_HISTORY_HIDDEN",
                    "AI 매매 내역은 대결 완료 후 공개됩니다.");
        }
    }

    private CompetitionException notFound() {
        return new CompetitionException(HttpStatus.NOT_FOUND, "COMPETITION_ACCOUNT_NOT_FOUND", "대결 계좌를 찾을 수 없습니다.");
    }
}
