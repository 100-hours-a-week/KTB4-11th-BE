package com.stock_spoon.river_be.competition.service;

import com.stock_spoon.river_be.competition.dto.CompetitionCreateRequest;
import com.stock_spoon.river_be.competition.dto.CompetitionCreateResponse;
import com.stock_spoon.river_be.account.entity.Account;
import com.stock_spoon.river_be.account.repository.AccountRepository;
import com.stock_spoon.river_be.competition.entity.Competition;
import com.stock_spoon.river_be.competition.entity.CompetitionParticipant;
import com.stock_spoon.river_be.competition.exception.CompetitionException;
import com.stock_spoon.river_be.competition.repository.CompetitionRepository;
import com.stock_spoon.river_be.competition.repository.CompetitionParticipantRepository;
import com.stock_spoon.river_be.user.repository.UserRepository;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CompetitionService {
    private final UserRepository users;
    private final AccountRepository accounts;
    private final CompetitionRepository competitions;
    private final CompetitionParticipantRepository participants;
    private final CompetitionTradingHours tradingHours;

    @Transactional // 대결·계좌 두 개·참가자 중 하나라도 저장에 실패하면 모두 롤백한다.
    public CompetitionCreateResponse createCompetition(Long userId, CompetitionCreateRequest request) {
        var now = tradingHours.now(); // 자정 경계에서도 한 요청은 같은 날짜·시각으로 판단한다.
        var user = users.findLockedById(userId).orElseThrow(() -> new CompetitionException(
                HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "사용자를 찾을 수 없습니다."));
        if (!user.isOnboardingCompleted()) {
            throw new CompetitionException(HttpStatus.CONFLICT, "ONBOARDING_REQUIRED", "먼저 온보딩을 완료해주세요.");
        }
        // 단일 필드의 필수값·범위는 @Valid로 검사하고 여기서는 대결 정책을 검사한다.
        if (!"user_vs_ai".equals(request.getCompetitionType())) {
            throw invalid("INVALID_COMPETITION_TYPE", "지원하지 않는 대결 유형입니다.");
        }
        if (request.getAiParticipantCount() != 1) {
            throw invalid("INVALID_AI_PARTICIPANT_COUNT", "v2 대결에는 AI 한 명만 참여할 수 있습니다.");
        }
        if (!request.getStartDate().equals(now.toLocalDate())) {
            throw invalid("INVALID_START_DATE", "대결 시작일은 오늘 날짜여야 합니다.");
        }
        if (ChronoUnit.DAYS.between(request.getStartDate(), request.getEndDate()) < 3) {
            throw invalid("INVALID_COMPETITION_PERIOD", "대결 기간은 최소 3일이어야 합니다.");
        }
        String name = request.getCompetitionName().trim();
        if (name.isBlank() || name.length() > 20) {
            throw invalid("INVALID_COMPETITION_NAME", "대결 이름은 공백이 아닌 20자 이하여야 합니다.");
        }
        if (accounts.existsByUserIdAndNameIgnoreCase(userId, name)) {
            throw new CompetitionException(HttpStatus.CONFLICT, "DUPLICATE_ACCOUNT_NAME", "이미 사용 중인 계좌 이름입니다.");
        }

        var competition = competitions.save(new Competition(user, name, Competition.Type.USER_VS_AI,
                request.getStartDate(), request.getEndDate(), request.getInitialCapital(), request.getAiParticipantCount()));
        if (tradingHours.isOpen(now)) competition.start(now.toInstant());

        // 두 계좌의 자금은 별개다. 사용자 계좌명만 대결명을 사용하고 AI 이름은 내부 식별용이다.
        var userAccount = accounts.save(new Account(user, name, request.getInitialCapital(), false));
        String aiName = nextAiAccountName(userId, competition.getId());
        var aiAccount = accounts.save(new Account(user, aiName, request.getInitialCapital(), true));
        participants.save(new CompetitionParticipant(competition, userAccount));
        participants.save(new CompetitionParticipant(competition, aiAccount));

        return new CompetitionCreateResponse("success", competition.getId(),
                competition.getState().name().toLowerCase(Locale.ROOT));
    }

    private String nextAiAccountName(Long userId, Long competitionId) {
        // ID가 길어져도 계좌명의 20자 제한 안에 들어가도록 36진수로 표현한다.
        String base = "AI-" + Long.toString(competitionId, 36);
        String name = base;
        int suffix = 1;
        while (accounts.existsByUserIdAndNameIgnoreCase(userId, name)) {
            String suffixText = "-" + Integer.toString(suffix++, 36);
            name = base.substring(0, Math.min(base.length(), 20 - suffixText.length())) + suffixText;
        }
        return name;
    }

    private CompetitionException invalid(String code, String message) {
        return new CompetitionException(HttpStatus.BAD_REQUEST, code, message);
    }
}
