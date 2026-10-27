package com.stock_spoon.river_be.user.repository;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;

/** [회원 탈퇴 추가] 탈퇴의 bulk 삭제는 JPA cascade를 거치지 않으므로 FK의 자식부터 명시적으로 삭제한다. */
@Repository
public class UserDeletionRepository {
    private final EntityManager em;

    // 상위 탈퇴 트랜잭션의 EntityManager로 삭제 쿼리를 실행한다.
    public UserDeletionRepository(EntityManager em) { this.em = em; }

    public void deleteAllForUser(long userId) {
        // SQL 삭제 전 미반영 변경을 먼저 반영한다. 중간 실패의 롤백은 상위 서비스 트랜잭션이 담당한다.
        em.flush();
        // 소유 회원 ID로만 범위를 제한한다. 활성 계좌만 고르는 조건은 사용하지 않는다.
        String accounts = "select account_id from accounts where user_id=:userId";
        String orders = "select order_id from trade_orders where account_id in (" + accounts + ")";
        String competitions = "select competition_id from competitions where user_id=:userId";
        // 1. 리포트 상세 -> 리포트·체결을 먼저 지워 주문을 참조하는 FK를 해제한다.
        delete("ai_report_reasoning", "report_id in (select report_id from ai_order_reports where order_id in (" + orders + "))", userId);
        delete("ai_order_reports", "order_id in (" + orders + ")", userId);
        delete("executions", "order_id in (" + orders + ")", userId);
        // 2. 단순 숫자 ID로 저장된 비동기 요청 이력까지 정리한 뒤 모든 상태의 주문과 보유 종목을 삭제한다.
        delete("order_request_records", "user_id=:userId or account_id in (" + accounts + ")", userId);
        delete("trade_orders", "account_id in (" + accounts + ")", userId);
        delete("holdings", "account_id in (" + accounts + ")", userId);
        // 3. 성과 -> 참가자·분석·판정 -> 대결 순으로 삭제해 참가자의 계좌 참조도 제거한다.
        delete("competition_daily_performances", "participant_id in (select participant_id from competition_participants where competition_id in (" + competitions + "))", userId);
        delete("competition_participants", "competition_id in (" + competitions + ")", userId);
        delete("competition_market_analyses", "competition_id in (" + competitions + ")", userId);
        delete("competition_judgment_summaries", "competition_id in (" + competitions + ")", userId);
        delete("competitions", "user_id=:userId", userId);
        // 4. 관심 등록과 모든 기기의 Refresh Token을 삭제한다. 공용 stocks 행은 유지한다.
        delete("watchlist_stocks", "user_id=:userId", userId);
        delete("refresh_token", "user_id=:userId", userId);
        // 5. 하위 참조가 정리된 계좌 -> OAuth -> 회원을 삭제한다. 재가입 시 기존 연결을 찾을 수 없다.
        delete("accounts", "user_id=:userId", userId);
        delete("user_oauth", "user_id=:userId", userId);
        delete("users", "user_id=:userId", userId);
        // bulk SQL로 삭제된 엔티티가 영속성 컨텍스트에 남아 후속 저장에 사용되지 않게 비운다.
        em.clear();
    }

    // 테이블·조건은 위의 고정 문자열만 사용하고 외부 입력인 userId는 SQL 파라미터로 바인딩한다.
    private void delete(String table, String condition, long userId) {
        em.createNativeQuery("delete from " + table + " where " + condition)
                .setParameter("userId", userId).executeUpdate();
    }
}
