package com.stock_spoon.river_be.user.service;

import com.stock_spoon.river_be.account.repository.AccountRepository;
import com.stock_spoon.river_be.auth.client.KakaoClient;
import com.stock_spoon.river_be.competition.repository.CompetitionRepository;
import com.stock_spoon.river_be.order.reservation.OrderReservationStore;
import com.stock_spoon.river_be.order.service.OrderSubscriptionService;
import com.stock_spoon.river_be.user.exception.UserNotFoundException;
import com.stock_spoon.river_be.user.repository.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** [회원 탈퇴 추가] 카카오 연결 해제와 전체 소유 데이터 삭제를 총괄하고 DB 커밋 후 예약을 정리한다. */
@Service
public class UserWithdrawalService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(UserWithdrawalService.class);
    private final UserRepository users;
    private final UserOAuthRepository oauth;
    private final AccountRepository accounts;
    private final CompetitionRepository competitions;
    private final UserDeletionRepository deletion;
    private final KakaoClient kakao;
    private final ObjectProvider<OrderReservationStore> reservations;
    private final OrderSubscriptionService subscriptions;
    private final TransactionTemplate tx;

    // 잠금 조회·DB 삭제·카카오 호출·예약 정리에 필요한 구성요소를 생성자 주입한다.
    public UserWithdrawalService(UserRepository users, UserOAuthRepository oauth, AccountRepository accounts,
            CompetitionRepository competitions, UserDeletionRepository deletion, KakaoClient kakao,
            ObjectProvider<OrderReservationStore> reservations, OrderSubscriptionService subscriptions,
            PlatformTransactionManager manager) {
        this.users = users; this.oauth = oauth; this.accounts = accounts; this.competitions = competitions;
        this.deletion = deletion; this.kakao = kakao; this.reservations = reservations; this.subscriptions = subscriptions;
        // DB 삭제는 하나의 트랜잭션이다. 카카오와 Redis의 변경까지 함께 롤백되는 것은 아니다.
        tx = new TransactionTemplate(manager);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /** 인증된 회원 ID로 동기 탈퇴한다. DB 오류가 전파되면 컨트롤러의 쿠키 만료도 실행되지 않는다. */
    public void withdraw(long userId) {
        var accountIds = tx.execute(status -> {
            // 같은 회원의 생성/탈퇴 요청을 직렬화한다. 외부 unlink는 타임아웃이 제한된 단일 호출이다.
            users.findLockedById(userId).orElseThrow(UserNotFoundException::new);
            // 대결 -> 계좌를 각각 ID 순으로 잠가 기존 정산·체결 경로와 잠금 순서를 맞춘다.
            competitions.findAllLockedByUserId(userId);
            var owned = accounts.findAllLockedByUserId(userId);
            // OAuth 연결이 있을 때만 카카오 식별자로 unlink한다. 실패하면 아래 DB 삭제에 진입하지 않는다.
            oauth.findByUserId(userId).ifPresent(link -> kakao.unlink(link.getProviderUserId()));
            // 삭제 후에도 Redis 키를 정리할 수 있도록 비활성 계좌까지 포함한 계좌 ID를 보관한다.
            var ids = owned.stream().map(a -> a.getId()).toList();
            // 자식부터 회원까지 삭제하며 어느 쿼리든 실패하면 DB 변경 전체를 롤백한다.
            deletion.deleteAllForUser(userId);
            return ids;
        });
        // 이 시점에는 DB가 커밋되었다. 큐 예약 저장소가 구성된 환경에서만 Redis를 정리한다.
        var store = reservations.getIfAvailable();
        if (store != null) for (long accountId : accountIds) {
            try { store.removeAll(accountId); }
            catch (RuntimeException error) {
                // DB는 이미 커밋되었다. 기존 주문 reconcile이 없는 계좌의 예약을 다시 정리한다.
                log.warn("event=withdrawal_reservation_cleanup_failed accountId={} causeType={}", accountId, error.getClass().getSimpleName());
            }
        }
        // 남은 대기 주문에 맞춰 종목 구독 수요를 갱신한다. 실패 재처리는 기존 refresh가 담당한다.
        subscriptions.refresh();
        log.info("event=user_withdrawal_completed");
    }
}
