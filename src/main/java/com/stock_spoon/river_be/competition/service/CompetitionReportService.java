package com.stock_spoon.river_be.competition.service;

import com.stock_spoon.river_be.competition.dto.CompetitionReportResponse;
import com.stock_spoon.river_be.competition.dto.CompetitionReportSaveRequest;
import com.stock_spoon.river_be.competition.entity.CompetitionMarketAnalysis;
import com.stock_spoon.river_be.competition.entity.CompetitionJudgmentSummary;
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

    @Transactional // 기존 내용을 지우고 새 내용을 저장하는 작업을 하나로 처리한다.
    public void save(long userId, long competitionId, CompetitionReportSaveRequest request) {
        // 같은 대결의 저장 요청이 동시에 들어오면 순서대로 처리한다.
        var competition = competitions.findLockedById(competitionId)
                .filter(c -> c.getUser().getId().equals(userId))
                .orElseThrow(() -> new CompetitionException(HttpStatus.NOT_FOUND,
                        "COMPETITION_NOT_FOUND", "대결을 찾을 수 없습니다."));

        // 재전송 시 기존 항목을 지우고 요청받은 내용으로 교체한다.
        analyses.deleteAll(analyses.findAllByCompetitionIdOrderByDateDescIdDesc(competitionId));
        summaries.deleteAll(summaries.findAllByCompetitionIdOrderByIdAsc(competitionId));

        for (var news : request.getNews()) {
            // AI 서버가 뉴스 날짜를 보내지 않으므로 null로 저장한다.
            analyses.save(new CompetitionMarketAnalysis(competition, null, news.getTitle(), news.getSummary()));
        }
        for (var thought : request.getThoughts()) {
            summaries.save(new CompetitionJudgmentSummary(competition, thought.getTitle(), thought.getText()));
        }
    }

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
