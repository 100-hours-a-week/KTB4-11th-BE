package com.stock_spoon.river_be.competition.service;

import com.stock_spoon.river_be.account.repository.AccountRepository;
import com.stock_spoon.river_be.competition.dto.CompetitionCancelResponse;
import com.stock_spoon.river_be.competition.entity.Competition;
import com.stock_spoon.river_be.competition.exception.CompetitionException;
import com.stock_spoon.river_be.competition.repository.CompetitionRepository;
import com.stock_spoon.river_be.competition.repository.CompetitionParticipantRepository;
import com.stock_spoon.river_be.order.entity.Order;
import com.stock_spoon.river_be.order.repository.OrderRepository;
import com.stock_spoon.river_be.order.messaging.OrderRequestTransactions;
import com.stock_spoon.river_be.order.service.OrderSubscriptionService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class CompetitionCancellationService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CompetitionCancellationService.class);
    private final CompetitionRepository competitions;
    private final CompetitionParticipantRepository participants;
    private final AccountRepository accounts;
    private final OrderRepository orders;
    private final ObjectProvider<OrderRequestTransactions> requests;
    private final OrderSubscriptionService subscriptions;
    private final CompetitionTradingHours tradingHours;
    private final TransactionTemplate tx;
    @PersistenceContext private EntityManager entityManager;

    public CompetitionCancellationService(CompetitionRepository competitions,
            CompetitionParticipantRepository participants, AccountRepository accounts, OrderRepository orders,
            ObjectProvider<OrderRequestTransactions> requests, OrderSubscriptionService subscriptions,
            CompetitionTradingHours tradingHours, PlatformTransactionManager manager) {
        this.competitions = competitions;
        this.participants = participants;
        this.accounts = accounts;
        this.orders = orders;
        this.requests = requests;
        this.subscriptions = subscriptions;
        this.tradingHours = tradingHours;
        // DB 커밋이 끝난 다음 Redis 예약 정리를 실행할 수 있도록 트랜잭션 범위를 지정한다.
        tx = new TransactionTemplate(manager);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public CompetitionCancelResponse cancel(long userId, long competitionId) {
        var accountIds = tx.execute(status -> {
            // 종료·시작 스케줄러와 같은 대결 잠금을 먼저 획득한다.
            var competition = competitions.findLockedById(competitionId)
                    .filter(c -> c.getUser().getId().equals(userId))
                    .orElseThrow(() -> new CompetitionException(HttpStatus.NOT_FOUND,
                            "COMPETITION_NOT_FOUND", "대결을 찾을 수 없습니다."));
            if (competition.getState() == Competition.State.COMPLETED) {
                throw new CompetitionException(HttpStatus.CONFLICT,
                        "COMPETITION_ALREADY_COMPLETED", "이미 완료된 대결은 취소할 수 없습니다.");
            }
            var links = participants.findAllByCompetitionIdOrderByIdAsc(competitionId);
            if (competition.getCompetitionType() == Competition.Type.AI_VS_AI) {
                throw new CompetitionException(HttpStatus.CONFLICT, "COMPETITION_TYPE_NOT_SUPPORTED",
                        "AI 간 대결의 취소는 아직 지원하지 않습니다.");
            }
            if (links.size() != 2 || links.stream().filter(p -> p.getAccount().isAiManaged()).count() != 1) {
                throw new CompetitionException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "INVALID_COMPETITION_PARTICIPANTS", "대결 참가자 정보를 확인할 수 없습니다.");
            }
            var ids = links.stream().map(p -> p.getAccount().getId()).sorted().toList();
            for (var id : ids) {
                var account = accounts.findLockedById(id).orElseThrow();
                // 잠금을 기다리는 동안 체결된 최신 잔액·상태를 다시 읽는다.
                entityManager.refresh(account);
                account.deactivate();
            }
            var now = tradingHours.now().toInstant();
            for (var order : orders.findAllByAccountIdsAndStatus(ids, Order.Status.PENDING)) order.cancel(now);
            competition.cancel();
            return ids;
        });

        // 커밋 후 실패는 취소를 되돌리지 않는다. 남은 예약은 기존 reconcile에서 다시 정리한다.
        var processing = requests.getIfAvailable();
        if (processing != null) {
            for (var id : accountIds) {
                try { processing.cleanupInactiveAccount(id); }
                catch (RuntimeException error) {
                    log.warn("event=competition_cancellation_cleanup_failed competitionId={} accountId={} causeType={}",
                            competitionId, id, error.getClass().getSimpleName(), error);
                }
            }
        }
        try { subscriptions.refresh(); }
        catch (RuntimeException error) {
            log.warn("event=competition_cancellation_subscription_failed competitionId={} causeType={}",
                    competitionId, error.getClass().getSimpleName(), error);
        }
        return new CompetitionCancelResponse(competitionId, "cancelled");
    }
}
