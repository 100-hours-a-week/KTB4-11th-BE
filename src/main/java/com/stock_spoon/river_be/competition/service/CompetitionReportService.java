package com.stock_spoon.river_be.competition.service;

import com.stock_spoon.river_be.competition.dto.CompetitionReportResponse;
import com.stock_spoon.river_be.competition.dto.CompetitionReportSaveRequest;
import com.stock_spoon.river_be.competition.entity.CompetitionMarketAnalysis;
import com.stock_spoon.river_be.competition.entity.CompetitionJudgmentSummary;
import com.stock_spoon.river_be.competition.entity.Competition;
import com.stock_spoon.river_be.competition.exception.CompetitionException;
import com.stock_spoon.river_be.competition.repository.*;
import java.util.List;
import java.util.Comparator;
import java.util.HashMap;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

@Service
@RequiredArgsConstructor // final 필드에 필요한 생성자를 만들어 Spring이 Repository를 주입하게 한다.
public class CompetitionReportService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CompetitionReportService.class);
    private final CompetitionRepository competitions;
    private final CompetitionMarketAnalysisRepository analyses;
    private final CompetitionJudgmentSummaryRepository summaries;
    private final CompetitionParticipantRepository participants;
    private final CompetitionAgentRepository agents;

    @Transactional // 기존 내용을 지우고 새 내용을 저장하는 작업을 하나로 처리한다.
    public void save(long userId, long competitionId, CompetitionReportSaveRequest request) {
        // 같은 대결의 저장 요청이 동시에 들어오면 순서대로 처리한다.
        var competition = competitions.findLockedById(competitionId)
                .filter(c -> c.getUser().getId().equals(userId))
                .orElseThrow(() -> new CompetitionException(HttpStatus.NOT_FOUND,
                        "COMPETITION_NOT_FOUND", "대결을 찾을 수 없습니다."));

        if (request.getParticipantId() == null || request.getParticipantId() <= 0) {
            throw new CompetitionException(HttpStatus.BAD_REQUEST, "INVALID_PARTICIPANT_ID", "AI 참가자 ID를 확인해주세요.");
        }
        var participant = participants.findById(request.getParticipantId())
                .filter(p -> p.getCompetition().getId().equals(competitionId)
                        && p.getAccount().isAiManaged() && p.getAccount().belongsTo(userId))
                .orElseThrow(() -> new CompetitionException(HttpStatus.NOT_FOUND,
                        "AI_PARTICIPANT_NOT_FOUND", "해당 대결의 AI 참가자를 찾을 수 없습니다."));

        // 재전송 시 해당 AI의 항목만 교체한다. 같은 대결의 다른 AI 보고서는 보존한다.
        analyses.deleteAll(analyses.findAllByCompetitionIdAndParticipantIdOrderByDateDescIdDesc(competitionId, participant.getId()));
        summaries.deleteAll(summaries.findAllByCompetitionIdAndParticipantIdOrderByIdAsc(competitionId, participant.getId()));

        for (var news : request.getNews()) {
            // AI 서버가 뉴스 날짜를 보내지 않으므로 null로 저장한다.
            analyses.save(new CompetitionMarketAnalysis(participant, null, news.getTitle(), news.getSummary()));
        }
        for (var thought : request.getThoughts()) {
            summaries.save(new CompetitionJudgmentSummary(participant, thought.getTitle(), thought.getText()));
        }
        // 본문 교체와 같은 트랜잭션에서 기록하므로 저장 실패 시 완료 표시도 롤백된다.
        participant.markReportReady();
    }

    // 참가자 상태와 본문을 같은 스냅샷에서 읽어 저장 중인 보고서의 일부가 섞이지 않게 한다.
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public CompetitionReportResponse get(long userId, long competitionId) {
        // 본인 대결인지 먼저 검사한다. 상태에 따른 공개 제한은 이 리포트에 적용하지 않는다.
        var competition = competitions.findById(competitionId)
                .filter(c -> c.getUser().getId().equals(userId))
                .orElseThrow(() -> new CompetitionException(HttpStatus.NOT_FOUND,
                        "COMPETITION_NOT_FOUND", "대결을 찾을 수 없습니다."));
        var links = participants.findAllByCompetitionIdOrderByIdAsc(competitionId);
        if (!CompetitionParticipants.valid(competition, links)) {
            log.error("event=competition_report_lookup_failed stage=participant_validation competitionId={} competitionType={} participantCount={} expectedAiCount={}",
                    competitionId, competition.getCompetitionType(), links.size(), competition.getAiParticipantCount());
            throw reportUnavailable();
        }
        boolean aiOnly = competition.getCompetitionType() == Competition.Type.AI_VS_AI;
        var positions = new HashMap<Long, Integer>();
        if (aiOnly) {
            var settings = agents.findAllByParticipantCompetitionIdOrderByPositionAsc(competitionId);
            if (!CompetitionParticipants.validStrategies(links, settings)) {
                log.error("event=competition_report_lookup_failed stage=strategy_validation competitionId={} participantCount={} strategyCount={}",
                        competitionId, links.size(), settings.size());
                throw reportUnavailable();
            }
            for (var agent : settings) positions.put(agent.getParticipant().getId(), agent.getPosition());
            links = links.stream().sorted(Comparator.comparingInt(p -> positions.get(p.getId()))).toList();
        }
        // 본문은 대결당 두 번 조회하고 참가자 ID로 묶어 AI마다 조회를 반복하지 않는다.
        var marketByParticipant = analyses.findAllByCompetitionIdOrderByDateDescIdDesc(competitionId).stream()
                .collect(Collectors.groupingBy(a -> a.getParticipant().getId()));
        var summaryByParticipant = summaries.findAllByCompetitionIdOrderByIdAsc(competitionId).stream()
                .collect(Collectors.groupingBy(s -> s.getParticipant().getId()));
        var reports = links.stream().filter(p -> p.getAccount().isAiManaged()).map(p -> {
            String name = aiOnly ? "AI " + (positions.get(p.getId()) + 1) : "AI";
            if (!p.isReportReady()) {
                return new CompetitionReportResponse.ReportItem(p.getId(), name, "pending", List.of(), List.of());
            }
            var marketItems = marketByParticipant.getOrDefault(p.getId(), List.of()).stream()
                    .map(a -> new CompetitionReportResponse.MarketAnalysisItem(a.getId(), a.getDate(), a.getTitle(), a.getContent())).toList();
            var summaryItems = summaryByParticipant.getOrDefault(p.getId(), List.of()).stream()
                    .map(s -> new CompetitionReportResponse.JudgmentSummaryItem(s.getTitle(), s.getContent())).toList();
            return new CompetitionReportResponse.ReportItem(p.getId(), name, "completed", marketItems, summaryItems);
        }).toList();
        // 미수신 AI도 pending으로 포함하므로 FE에서 모든 AI의 버튼과 준비 상태를 표시할 수 있다.
        return new CompetitionReportResponse(competition.getId(), competition.getCompetitionName(),
                competition.getStartDate(), competition.getEndDate(), reports);
    }

    private CompetitionException reportUnavailable() {
        return new CompetitionException(HttpStatus.SERVICE_UNAVAILABLE,
                "COMPETITION_REPORT_UNAVAILABLE", "대결 리포트의 참가자 정보를 확인할 수 없습니다.");
    }
}
