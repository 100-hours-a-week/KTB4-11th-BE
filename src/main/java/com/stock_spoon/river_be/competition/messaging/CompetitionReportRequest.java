package com.stock_spoon.river_be.competition.messaging;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import lombok.Getter;

@Getter
public class CompetitionReportRequest {
    @JsonProperty("competition_id")
    private final long competitionId;
    @JsonProperty("participant_id")
    private final long participantId;
    @JsonProperty("user_id")
    private final long userId;
    @JsonProperty("portfolio_reason_ids")
    private final List<String> portfolioReasonIds;

    public CompetitionReportRequest(long competitionId, long participantId, long userId, List<String> portfolioReasonIds) {
        if (competitionId <= 0 || participantId <= 0 || userId <= 0 || portfolioReasonIds == null) {
            throw new IllegalArgumentException("리포트 요청의 대결·참가자·사용자 ID와 판단 근거 목록을 확인하세요.");
        }
        for (String id : portfolioReasonIds) {
            if (id == null || !id.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
                throw new IllegalArgumentException("판단 근거 ID는 UUID 형식이어야 합니다.");
            }
        }
        this.competitionId = competitionId;
        this.participantId = participantId;
        this.userId = userId;
        this.portfolioReasonIds = List.copyOf(portfolioReasonIds);
    }
}
