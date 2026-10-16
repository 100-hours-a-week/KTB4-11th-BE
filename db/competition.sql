-- users, accounts 테이블 생성 후 적용한다. 실행 전 대상 DB와 기존 테이블을 확인한다.
CREATE TABLE competitions (
    competition_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    competition_name VARCHAR(20) NOT NULL,
    competition_type ENUM('USER_VS_AI') NOT NULL,
    initial_capital BIGINT NOT NULL,
    ai_participant_count INT NOT NULL,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    state ENUM('READY', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED') NOT NULL,
    started_at DATETIME(6) NULL,
    completed_at DATETIME(6) NULL,
    winner ENUM('USER', 'AI', 'DRAW') NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    KEY ix_competitions_state_dates (state, start_date, end_date),
    CONSTRAINT fk_competitions_user FOREIGN KEY (user_id) REFERENCES users(user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE competition_participants (
    participant_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    competition_id BIGINT NOT NULL,
    account_id BIGINT NOT NULL,
    final_asset_value BIGINT NULL,
    final_return_percent DECIMAL(19,4) NULL,
    UNIQUE KEY uk_competition_participant_account (account_id),
    CONSTRAINT fk_participants_competition FOREIGN KEY (competition_id) REFERENCES competitions(competition_id),
    CONSTRAINT fk_participants_account FOREIGN KEY (account_id) REFERENCES accounts(account_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
