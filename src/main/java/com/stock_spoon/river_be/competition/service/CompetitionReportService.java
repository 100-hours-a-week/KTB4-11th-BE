package com.stock_spoon.river_be.competition.service;

import com.stock_spoon.river_be.competition.dto.CompetitionReportResponse;
import com.stock_spoon.river_be.competition.exception.CompetitionException;
import com.stock_spoon.river_be.competition.repository.*;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor // final 필드에 필요한 생성자를 만들어 Spring이 Repository를 주입하게 한다.
public class CompetitionReportService {
    private final CompetitionRepository competitions;
    private final CompetitionMarketAnalysisRepository analyses;
    private final CompetitionJudgmentSummaryRepository summaries;

    @Transactional(readOnly = true) // 저장된 내용만 조회하며 AI 생성 요청이나 데이터 변경은 하지 않는다.
    public CompetitionReportResponse get(long userId, long competitionId) {
        // 본인 대결인지 먼저 검사한다. 상태에 따른 공개 제한은 이 리포트에 적용하지 않는다.
        var competition = competitions.findById(competitionId)
                .filter(c -> c.getUser().getId().equals(userId))
                .orElseThrow(() -> new CompetitionException(HttpStatus.NOT_FOUND,
                        "COMPETITION_NOT_FOUND", "대결을 찾을 수 없습니다."));
        var marketItems = analyses.findAllByCompetitionIdOrderByDateDescIdDesc(competitionId).stream()
                .map(a -> new CompetitionReportResponse.MarketAnalysisItem(a.getId(), a.getDate(), a.getTitle(), a.getContent()))
                .toList();
        var summaryItems = summaries.findAllByCompetitionIdOrderByIdAsc(competitionId).stream()
                .map(s -> new CompetitionReportResponse.JudgmentSummaryItem(s.getTitle(), s.getContent())).toList();
        // 본문을 함께 반환하므로 FE는 추가 요청 없이 접기·펼치기를 처리할 수 있다.
        return new CompetitionReportResponse(competition.getId(), competition.getCompetitionName(),
                competition.getStartDate(), competition.getEndDate(), competition.getState().name().toLowerCase(Locale.ROOT),
                marketItems, summaryItems);
    }
}
