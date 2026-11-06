package com.stock_spoon.river_be.competition.service;

import com.stock_spoon.river_be.competition.entity.*;
import java.util.*;

/** 유형별 참가자 구성과 계좌 연결을 공통 검증한다. */
public final class CompetitionParticipants {
    private CompetitionParticipants() {}
    public static boolean valid(Competition competition, List<CompetitionParticipant> links) {
        long ai = links.stream().filter(p -> p.getAccount().isAiManaged()).count();
        boolean shape = competition.getCompetitionType() == Competition.Type.AI_VS_AI
                ? links.size() >= 2 && links.size() <= 4 && links.size() == competition.getAiParticipantCount() && ai == links.size()
                : links.size() == 2 && ai == 1 && competition.getAiParticipantCount() == 1;
        return shape && links.stream().map(p -> p.getAccount().getId()).distinct().count() == links.size()
                && links.stream().allMatch(p -> p.getCompetition().getId().equals(competition.getId())
                        && p.getAccount().belongsTo(competition.getUser().getId()));
    }
    public static void requireValid(Competition competition, List<CompetitionParticipant> links) {
        if (!valid(competition, links)) throw new IllegalStateException("대결 참가자 구성을 확인할 수 없습니다.");
    }
    public static boolean validStrategies(List<CompetitionParticipant> links, List<CompetitionAgent> settings) {
        if (settings.size() != links.size()) return false;
        var ids = links.stream().map(CompetitionParticipant::getId).collect(java.util.stream.Collectors.toSet());
        var settingIds = settings.stream().map(a -> a.getParticipant().getId()).collect(java.util.stream.Collectors.toSet());
        var positions = settings.stream().map(CompetitionAgent::getPosition).collect(java.util.stream.Collectors.toSet());
        if (!ids.equals(settingIds) || positions.size() != links.size()) return false;
        for (int i = 0; i < links.size(); i++) if (!positions.contains(i)) return false;
        return true;
    }
    /** 표시 수익률 반올림 전에 자산/초기금 비율을 비교한다. */
    public static int compare(CompetitionParticipant left, CompetitionParticipant right) {
        return java.math.BigDecimal.valueOf(left.getFinalAssetValue())
                .multiply(java.math.BigDecimal.valueOf(right.getAccount().getInitialCapital()))
                .compareTo(java.math.BigDecimal.valueOf(right.getFinalAssetValue())
                        .multiply(java.math.BigDecimal.valueOf(left.getAccount().getInitialCapital())));
    }
    public static int rank(CompetitionParticipant participant, List<CompetitionParticipant> links) {
        return 1 + (int) links.stream().filter(other -> compare(other, participant) > 0).count();
    }
}
