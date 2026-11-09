package com.stock_spoon.river_be.account.service;

import com.stock_spoon.river_be.account.exception.AccountException;
import com.stock_spoon.river_be.account.repository.AccountRepository;
import com.stock_spoon.river_be.order.entity.Order;
import com.stock_spoon.river_be.order.messaging.OrderRequestTransactions;
import com.stock_spoon.river_be.order.repository.OrderRepository;
import com.stock_spoon.river_be.order.service.OrderSubscriptionService;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** [계좌 비활성화 추가] 대기 주문 취소와 계좌 비활성화를 함께 커밋하고 자산·투자 기록은 보존한다. */
@Service
public class AccountDeletionService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AccountDeletionService.class);
    private final AccountRepository accounts;
    private final OrderRepository orders;
    private final ObjectProvider<OrderRequestTransactions> requests;
    private final OrderSubscriptionService subscriptions;
    private final TransactionTemplate tx;
    private final Clock clock = Clock.systemUTC();

    public AccountDeletionService(AccountRepository accounts, OrderRepository orders,
            ObjectProvider<OrderRequestTransactions> requests, OrderSubscriptionService subscriptions,
            PlatformTransactionManager manager) {
        this.accounts=accounts; this.orders=orders; this.requests=requests; this.subscriptions=subscriptions;
        tx=new TransactionTemplate(manager);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public void delete(long userId, long accountId) {
        tx.executeWithoutResult(status -> {
            // 단건 활성 조회를 쓰지 않아 이미 비활성인 계좌도 소유권 확인 후 같은 성공 응답을 제공한다.
            var account=accounts.findLockedById(accountId).filter(a -> a.belongsTo(userId))
                    .orElseThrow(() -> new AccountException(HttpStatus.NOT_FOUND,"ACCOUNT_NOT_FOUND","계좌를 찾을 수 없습니다."));
            // 대결 상태와 무관하게 양측 계좌의 개별 비활성화를 막는다. 거절 시 주문 상태도 그대로다.
            if(accounts.findCompetitionByAccountId(accountId).isPresent())
                throw new AccountException(HttpStatus.CONFLICT,"COMPETITION_ACCOUNT_DELETE_NOT_ALLOWED","대결 계좌는 개별 삭제할 수 없습니다.");
            var now=clock.instant();
            for(var order:orders.findAllByAccountIdAndStatus(accountId,Order.Status.PENDING)) order.cancel(now);
            account.deactivate();
        });
        // 커밋 후 Redis 전용 정리가 실패해도 이미 끝난 비활성화를 실패로 응답하지 않는다. 기존 reconcile이 재처리한다.
        var processing=requests.getIfAvailable();
        if(processing!=null) {
            try { processing.cleanupInactiveAccount(accountId); }
            catch(RuntimeException error) {
                log.warn("event=account_deactivation_cleanup_failed accountId={} causeType={}",accountId,error.getClass().getSimpleName());
            }
        }
        subscriptions.refresh();
        log.info("event=account_deactivated accountId={}",accountId);
    }
}
