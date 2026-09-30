-- 기존 데이터에 total_cost <= 0인 행이 있으면 변경이 실패합니다. 데이터는 자동 수정하지 않습니다.
ALTER TABLE holdings
    DROP CHECK ck_holdings_total_cost,
    ADD CONSTRAINT ck_holdings_total_cost CHECK (total_cost > 0);
