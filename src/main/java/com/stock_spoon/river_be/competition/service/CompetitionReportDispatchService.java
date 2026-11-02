package com.stock_spoon.river_be.competition.service;

import com.stock_spoon.river_be.competition.entity.Competition;
import com.stock_spoon.river_be.competition.entity.CompetitionReportOutbox;
import com.stock_spoon.river_be.competition.messaging.CompetitionReportRequest;
import com.stock_spoon.river_be.competition.messaging.CompetitionReportRequestPublisher;
import com.stock_spoon.river_be.competition.repository.CompetitionParticipantRepository;
import com.stock_spoon.river_be.competition.repository.CompetitionReportOutboxRepository;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "competition.report.queue.enabled", havingValue = "true")
public class CompetitionReportDispatchService {
    private static final Logger log = LoggerFactory.getLogger(CompetitionReportDispatchService.class);
    private final CompetitionReportOutboxRepository outboxes;
    private final CompetitionParticipantRepository participants;
    private final CompetitionReportRequestPublisher publisher;

    // 같은 Outbox의 동시 전송을 막는다. 전송 실패 시 PENDING으로 롤백하고 다음 주기에 재시도한다.
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public String dispatch(Long outboxId) {
        var outbox = outboxes.findLockedById(outboxId).orElse(null);
        if (outbox == null || outbox.getStatus() != CompetitionReportOutbox.Status.PENDING) return null;
        var competition = outbox.getCompetition();
        String stage = "message_build";
        try {
            if (competition.getState() != Competition.State.COMPLETED) {
                throw new IllegalStateException("완료된 대결만 리포트를 요청할 수 있습니다.");
            }
            var aiParticipants = participants.findAllByCompetitionIdOrderByIdAsc(competition.getId()).stream()
                    .filter(p -> p.getAccount().isAiManaged()).toList();
            if (aiParticipants.size() != 1) throw new IllegalStateException("리포트를 요청할 AI 참가자 한 명을 확인할 수 없습니다.");
            var ai = aiParticipants.getFirst();
            var originalIds = outboxes.findExecutedAiReasonIds(ai.getAccount().getId());
            long missingCount = originalIds.stream().filter(Objects::isNull).count();
            if (missingCount > 0) {
                log.warn("event=competition_report_reason_ids_missing competitionId={} participantId={} requestId={} missingOrderCount={}",
                        competition.getId(), ai.getId(), outbox.getRequestId(), missingCount);
            }
            // 과거 UUID를 임의 생성하지 않는다. 사용 가능한 원본 ID만 주문 순서대로 중복 제거한다.
            var reasonIds = originalIds.stream().filter(Objects::nonNull).distinct().toList();
            var payload = new CompetitionReportRequest(competition.getId(), ai.getId(), competition.getUser().getId(), reasonIds);
            stage = "sqs_send";
            publisher.publish(outbox.getRequestId(), payload);
            stage = "sent_save";
            outbox.markSent();
            outboxes.flush();
            return outbox.getRequestId();
        } catch (RuntimeException error) {
            log.error("event=competition_report_dispatch_failed stage={} outboxId={} competitionId={} requestId={} causeType={}",
                    stage, outboxId, competition.getId(), outbox.getRequestId(), error.getClass().getSimpleName(), error);
            throw error;
        }
    }
}
