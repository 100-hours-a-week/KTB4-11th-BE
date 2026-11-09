-- 기존 competitions / competition_participants 테이블 준비 후 한 번 적용한다.
-- 애플리케이션 배포 전에 적용한다. 이 파일은 자동 실행되지 않는다.
ALTER TABLE competitions MODIFY COLUMN competition_type ENUM('USER_VS_AI', 'AI_VS_AI') NOT NULL;

CREATE TABLE competition_agents (
    agent_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    participant_id BIGINT NOT NULL,
    agent_position INT NOT NULL,
    risk_level INT NOT NULL,
    additional_request LONGTEXT NOT NULL,
    UNIQUE KEY uk_competition_agents_participant (participant_id),
    CONSTRAINT fk_competition_agents_participant FOREIGN KEY (participant_id)
        REFERENCES competition_participants(participant_id),
    CONSTRAINT ck_competition_agents_risk CHECK (risk_level BETWEEN 1 AND 5),
    CONSTRAINT ck_competition_agents_position CHECK (agent_position >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE competition_agent_preferred_themes (
    agent_id BIGINT NOT NULL,
    theme_position INT NOT NULL,
    theme VARCHAR(255) NOT NULL,
    PRIMARY KEY (agent_id, theme_position),
    CONSTRAINT fk_preferred_themes_agent FOREIGN KEY (agent_id) REFERENCES competition_agents(agent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE competition_agent_excluded_themes (
    agent_id BIGINT NOT NULL,
    theme_position INT NOT NULL,
    theme VARCHAR(255) NOT NULL,
    PRIMARY KEY (agent_id, theme_position),
    CONSTRAINT fk_excluded_themes_agent FOREIGN KEY (agent_id) REFERENCES competition_agents(agent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
