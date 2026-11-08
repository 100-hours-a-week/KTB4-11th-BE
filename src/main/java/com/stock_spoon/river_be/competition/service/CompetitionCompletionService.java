package com.stock_spoon.river_be.competition.service;

import com.stock_spoon.river_be.account.repository.AccountRepository;
import com.stock_spoon.river_be.competition.entity.Competition;
import com.stock_spoon.river_be.competition.entity.CompetitionReportOutbox;
import com.stock_spoon.river_be.competition.repository.CompetitionReportOutboxRepository;
import com.stock_spoon.river_be.competition.repository.CompetitionRepository;
import com.stock_spoon.river_be.competition.repository.CompetitionParticipantRepository;
import com.stock_spoon.river_be.holding.repository.HoldingRepository;
import com.stock_spoon.river_be.market.kiwoom.KiwoomMarketClient;
import com.stock_spoon.river_be.order.entity.Order;
import com.stock_spoon.river_be.order.repository.OrderRepository;
import java.util.HashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CompetitionCompletionService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CompetitionCompletionService.class);
    private final CompetitionRepository competitions;
    private final CompetitionParticipantRepository participants;
    private final com.stock_spoon.river_be.competition.repository.CompetitionAgentRepository agents;
    private final AccountRepository accounts;
    private final HoldingRepository holdings;
    private final OrderRepository orders;
    private final KiwoomMarketClient market;
    private final CompetitionTradingHours tradingHours;
    private final CompetitionDailyPerformanceService dailyPerformances;
    private final CompetitionReportOutboxRepository reportOutboxes;
    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entityManager;

    // 대결별로 별도 트랜잭션을 사용한다. 정산 실패 시 해당 대결의 변경은 모두 롤백된다.
    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void completeCompetition(Long competitionId) {
        var now = tradingHours.now();
        // [회원 탈퇴 수정] 스케줄러의 후보 조회 후 삭제된 대결은 정산 오류 대신 정상 종료한다.
        var competition = competitions.findLockedById(competitionId).orElse(null);
        if (competition == null) return;
        if (competition.getState() != Competition.State.IN_PROGRESS
                || now.toInstant().isBefore(competition.tradingEndsAt())) return;

        var links = participants.findAllByCompetitionIdOrderByIdAsc(competitionId);
        CompetitionParticipants.requireValid(competition, links);
        if (competition.getCompetitionType() == Competition.Type.AI_VS_AI
                && !CompetitionParticipants.validStrategies(links, agents.findAllByParticipantCompetitionIdOrderByPositionAsc(competitionId))) {
            throw new IllegalStateException("AI 참가자의 전략 연결을 확인할 수 없습니다.");
        }
        // 주문 체결과 같은 계좌 잠금을 잡아 평가 중 자산이 바뀌지 않게 한다. 잠금 순서는 ID 순이다.
        var accountIds = links.stream().map(p -> p.getAccount().getId()).sorted().toList();
        for (Long id : accountIds) {
            var account = accounts.findLockedById(id).orElseThrow();
            // 잠금을 기다리던 중 먼저 시작한 체결이 커밋했을 수 있어 최신 잔액으로 다시 읽는다.
            entityManager.refresh(account);
        }
        var values = new HashMap<Long, Long>();
        for (var link : links) values.put(link.getAccount().getId(), link.getAccount().getCashBalance());
        var prices = new HashMap<String, Long>();
        for (var holding : holdings.findAllByAccountIds(accountIds)) {
            long price = prices.computeIfAbsent(holding.getStockCode(),
                    code -> market.closingPrice(code, competition.tradingEndDate()));
            if (price <= 0 || holding.getQuantity() <= 0) throw new IllegalStateException("종가와 보유 수량을 확인하세요.");
            values.merge(holding.getAccountId(), Math.multiplyExact(price, holding.getQuantity()), Math::addExact);
        }
        for (var link : links) link.recordFinalAssets(values.get(link.getAccount().getId()));
        Competition.Winner winner;
        if (competition.getCompetitionType() == Competition.Type.AI_VS_AI) {
            long leaders = links.stream().filter(p -> CompetitionParticipants.rank(p, links) == 1).count();
            winner = leaders == 1 ? Competition.Winner.AI : Competition.Winner.DRAW;
        } else {
            var user = links.stream().filter(p -> !p.getAccount().isAiManaged()).findFirst().orElseThrow();
            var ai = links.stream().filter(p -> p.getAccount().isAiManaged()).findFirst().orElseThrow();
            int comparison = CompetitionParticipants.compare(user, ai);
            winner = comparison > 0 ? Competition.Winner.USER : comparison < 0 ? Competition.Winner.AI : Competition.Winner.DRAW;
        }
        for (var pending : orders.findAllByAccountIdsAndStatus(accountIds, Order.Status.PENDING)) {
            pending.cancel(now.toInstant());
        }
        competition.complete(winner, now.toInstant());
        dailyPerformances.recordFinalPerformances(competitionId);
        // AI마다 요청을 만든다. 하나라도 저장에 실패하면 대결 완료와 모든 요청을 함께 롤백한다.
        for (var participant : links) {
            if (!participant.getAccount().isAiManaged()) continue;
            String requestId = java.util.UUID.randomUUID().toString();
            try {
                reportOutboxes.saveAndFlush(new CompetitionReportOutbox(competition, participant, requestId));
            } catch (RuntimeException error) {
                log.error("event=competition_report_outbox_creation_failed stage=outbox_save competitionId={} participantId={} requestId={} causeType={}",
                        competitionId, participant.getId(), requestId, error.getClass().getSimpleName(), error);
                throw error;
            }
        }
        // 최종 결과와 기록은 보존하고 양측 계좌를 일반 계좌 목록·거래 대상에서 제외한다.
        // 정산과 같은 트랜잭션이므로 커밋 실패 시 비활성화도 함께 롤백된다.
        for (var link : links) link.getAccount().deactivate();
    }
}
