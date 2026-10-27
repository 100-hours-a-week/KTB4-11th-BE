package com.stock_spoon.river_be.competition.service;

import com.stock_spoon.river_be.account.repository.AccountRepository;
import com.stock_spoon.river_be.competition.entity.Competition;
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
    private final CompetitionRepository competitions;
    private final CompetitionParticipantRepository participants;
    private final AccountRepository accounts;
    private final HoldingRepository holdings;
    private final OrderRepository orders;
    private final KiwoomMarketClient market;
    private final CompetitionTradingHours tradingHours;
    private final CompetitionDailyPerformanceService dailyPerformances;
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
        if (links.size() != 2 || links.stream().filter(p -> p.getAccount().isAiManaged()).count() != 1) {
            throw new IllegalStateException("사용자와 AI 참가자를 확인할 수 없습니다.");
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
        var user = links.stream().filter(p -> !p.getAccount().isAiManaged()).findFirst().orElseThrow();
        var ai = links.stream().filter(p -> p.getAccount().isAiManaged()).findFirst().orElseThrow();
        for (var link : links) link.recordFinalAssets(values.get(link.getAccount().getId()));
        // 반올림된 표시 수익률로 비교하지 않는다. 자산/초기자금 비율을 교차 곱해 비교한다.
        var userRatio = java.math.BigDecimal.valueOf(user.getFinalAssetValue())
                .multiply(java.math.BigDecimal.valueOf(ai.getAccount().getInitialCapital()));
        var aiRatio = java.math.BigDecimal.valueOf(ai.getFinalAssetValue())
                .multiply(java.math.BigDecimal.valueOf(user.getAccount().getInitialCapital()));
        int comparison = userRatio.compareTo(aiRatio);
        var winner = comparison > 0 ? Competition.Winner.USER : comparison < 0 ? Competition.Winner.AI : Competition.Winner.DRAW;
        for (var pending : orders.findAllByAccountIdsAndStatus(accountIds, Order.Status.PENDING)) {
            pending.cancel(now.toInstant());
        }
        competition.complete(winner, now.toInstant());
        dailyPerformances.recordFinalPerformances(competitionId);
    }
}
