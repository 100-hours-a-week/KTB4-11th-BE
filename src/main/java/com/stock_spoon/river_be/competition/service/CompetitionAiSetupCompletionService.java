package com.stock_spoon.river_be.competition.service;

import com.stock_spoon.river_be.competition.dto.CompetitionAiSetupCompleteRequest;
import com.stock_spoon.river_be.competition.entity.Competition;
import com.stock_spoon.river_be.competition.entity.CompetitionAiSetupOutbox;
import com.stock_spoon.river_be.competition.exception.CompetitionException;
import com.stock_spoon.river_be.competition.repository.CompetitionRepository;
import com.stock_spoon.river_be.competition.repository.CompetitionAiSetupOutboxRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CompetitionAiSetupCompletionService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CompetitionAiSetupCompletionService.class);
    private final CompetitionRepository competitions;
    private final CompetitionAiSetupOutboxRepository outboxes;
    private final CompetitionTradingHours hours;
    private final jakarta.validation.Validator validator;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void complete(long userId, long competitionId, CompetitionAiSetupCompleteRequest request) {
        if (request == null || !validator.validate(request).isEmpty()) {
            throw new CompetitionException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "설정 요청 ID를 확인해주세요.");
        }
        // 발송·취소·시작과 같은 대결 잠금으로 중복 통지와 상태 변경을 직렬화한다.
        var competition = competitions.findLockedById(competitionId)
                .filter(c -> c.getUser().getId().equals(userId))
                .orElseThrow(() -> new CompetitionException(HttpStatus.NOT_FOUND,
                        "COMPETITION_NOT_FOUND", "대결을 찾을 수 없습니다."));
        if (competition.getCompetitionType() != Competition.Type.AI_VS_AI) {
            throw new CompetitionException(HttpStatus.CONFLICT, "COMPETITION_TYPE_NOT_SUPPORTED", "AI 간 대결만 설정 완료할 수 있습니다.");
        }
        var outbox = outboxes.findByCompetitionIdAndEventId(competitionId, request.eventId())
                .orElseThrow(() -> new CompetitionException(HttpStatus.NOT_FOUND,
                        "AI_SETUP_REQUEST_NOT_FOUND", "해당 대결의 설정 요청을 찾을 수 없습니다."));
        if (competition.getState() == Competition.State.CANCELLED || outbox.getStatus() == CompetitionAiSetupOutbox.Status.CANCELLED) {
            throw new CompetitionException(HttpStatus.CONFLICT, "COMPETITION_CANCELLED", "취소된 대결입니다.");
        }
        // HTTP 응답 유실에 따른 재시도는 최초 완료 시각을 보존한다. 시작·종료 후에도 성공을 재응답한다.
        if (competition.getAiSetupCompletedAt() != null) return;
        var now = hours.now().toInstant();
        if (competition.getState() != Competition.State.READY || !now.isBefore(competition.tradingEndsAt())) {
            throw new CompetitionException(HttpStatus.CONFLICT, "COMPETITION_SETUP_EXPIRED", "설정 완료를 받을 수 있는 대결 기간이 지났습니다.");
        }
        competition.markAiSetupCompleted(now);
        // AI 소비가 확인되면 SQS 발송 후 DB 커밋 실패로 남은 PENDING도 전송 완료로 복구한다.
        outbox.markSent();
        log.info("event=competition_ai_setup_completed competitionId={} eventId={}", competitionId, request.eventId());
    }
}
