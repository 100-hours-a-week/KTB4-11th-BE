package com.stock_spoon.river_be.competition.service;

import com.stock_spoon.river_be.competition.dto.CompetitionCreateRequest;
import com.stock_spoon.river_be.competition.dto.CompetitionCreateResponse;
import com.stock_spoon.river_be.competition.dto.CompetitionNameAvailabilityResponse;
import com.stock_spoon.river_be.competition.dto.CompetitionListResponse;
import com.stock_spoon.river_be.competition.dto.CompetitionListCursor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import com.stock_spoon.river_be.competition.dto.CompetitionDetailResponse;
import com.stock_spoon.river_be.competition.dto.CompetitionResultResponse;
import com.stock_spoon.river_be.competition.repository.CompetitionDailyPerformanceRepository;
import java.math.BigDecimal;
import com.stock_spoon.river_be.account.service.AccountService;
import java.util.stream.Collectors;
import com.stock_spoon.river_be.account.entity.Account;
import com.stock_spoon.river_be.account.repository.AccountRepository;
import com.stock_spoon.river_be.competition.entity.Competition;
import com.stock_spoon.river_be.competition.entity.CompetitionParticipant;
import com.stock_spoon.river_be.competition.exception.CompetitionException;
import com.stock_spoon.river_be.competition.logging.CompetitionCreationLog;
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
    private final AccountService accountService;
    private final CompetitionDailyPerformanceRepository dailyPerformances;
    private final CompetitionAgentCreationService agentCreation;
    private final jakarta.validation.Validator validator;
    private final CompetitionDetailService detailService;

    @Transactional(readOnly = true) // 이름의 사용 가능 여부만 확인한다.
    public CompetitionNameAvailabilityResponse checkNameAvailability(Long userId, String competitionName) {
        // 생성 요청 DTO와 동일하게 공백 여부와 원본 문자열 길이를 먼저 검사한다.
        if (competitionName == null || competitionName.isBlank() || competitionName.length() > 20) {
            return new CompetitionNameAvailabilityResponse(false);
        }

        // 생성 시 계좌명으로 저장할 값의 앞뒤 공백을 제거한다.
        String name = competitionName.trim();
        if (name.isBlank()) {
            return new CompetitionNameAvailabilityResponse(false);
        }
        // 활성·비활성 계좌를 모두 조회해 종료된 대결명도 중복으로 판단한다.
        boolean duplicate = accounts.existsByUserIdAndNameIgnoreCase(userId, name)
                || competitions.existsByUserIdAndCompetitionNameIgnoreCase(userId, name);
        return new CompetitionNameAvailabilityResponse(!duplicate);
    }

    @Transactional(readOnly = true)
    public CompetitionResultResponse getCompetitionResult(Long userId, Long competitionId) {
        // 1. 소유권부터 확인한 뒤 완료 상태를 검사한다. 날짜가 지났어도 정산 전이면 결과를 공개하지 않는다.
        var competition = competitions.findByIdAndUserId(competitionId, userId)
                .orElseThrow(() -> new CompetitionException(HttpStatus.NOT_FOUND,
                        "COMPETITION_NOT_FOUND", "대결을 찾을 수 없습니다."));
        if (competition.getState() != Competition.State.COMPLETED) {
            throw new CompetitionException(HttpStatus.CONFLICT, "COMPETITION_NOT_COMPLETED", "아직 완료되지 않은 대결입니다.");
        }

        // 2. 참가자 저장 순서와 관계없이 계좌의 AI 운용 여부로 사용자·AI를 구분한다.
        var links = participants.findAllByCompetitionIdOrderByIdAsc(competitionId);
        if (links.size() != 2 || links.stream().filter(p -> p.getAccount().isAiManaged()).count() != 1) {
            throw resultUnavailable();
        }
        var user = links.stream().filter(p -> !p.getAccount().isAiManaged()).findFirst().orElseThrow();
        var ai = links.stream().filter(p -> p.getAccount().isAiManaged()).findFirst().orElseThrow();
        if (competition.getWinner() == null || user.getFinalReturnPercent() == null || ai.getFinalReturnPercent() == null) {
            throw resultUnavailable();
        }

        // 3. {날짜: {참가자 ID: 수익률}}로 묶는다. TreeMap은 날짜가 빠른 순서로 정렬해 준다.
        // 없는 쪽의 값은 Map.get()이 null을 반환한다. 기록을 만들어 넣거나 현재가로 계산하지 않는다.
        var byDate = new java.util.TreeMap<java.time.LocalDate, java.util.Map<Long, BigDecimal>>();
        for (var record : dailyPerformances.findAllByParticipantCompetitionId(competitionId)) {
            byDate.computeIfAbsent(record.getRecordDate(), date -> new java.util.HashMap<>())
                    .put(record.getParticipant().getId(), record.getCumulativeReturnPercent());
        }
        var history = byDate.entrySet().stream().map(day -> new CompetitionResultResponse.DailyPerformanceItem(
                day.getKey(), day.getValue().get(user.getId()), day.getValue().get(ai.getId()))).toList();

        // 4. account_id는 결과 화면 하단에서 참가 계좌의 매매 내역을 조회할 때 사용한다.
        var participantItems = links.stream().map(link -> {
            boolean isAi = link.getAccount().isAiManaged();
            return new CompetitionResultResponse.ParticipantItem(link.getId(), isAi ? "ai" : "user",
                    link.getAccount().getId(), isAi ? "AI" : "나");
        }).toList();
        return new CompetitionResultResponse(competition.getId(), competition.getCompetitionName(),
                competition.getCompetitionType().name().toLowerCase(Locale.ROOT), "completed",
                competition.getStartDate(), competition.getEndDate(), null,
                competition.getWinner().name().toLowerCase(Locale.ROOT), user.getFinalReturnPercent(),
                ai.getFinalReturnPercent(), participantItems, history);
    }

    private CompetitionException resultUnavailable() {
        return new CompetitionException(HttpStatus.INTERNAL_SERVER_ERROR,
                "COMPETITION_RESULT_UNAVAILABLE", "확정된 대결 결과를 확인할 수 없습니다.");
    }

    public CompetitionDetailResponse getCompetitionDetail(Long userId, Long competitionId) {
        return detailService.get(userId, competitionId);
    }

    public CompetitionListResponse listCompetitions(Long userId, String cursor, Integer limit, String state) {
        // 1. 요청을 먼저 검사한다. 필터가 바뀌면 이전 필터의 커서는 사용할 수 없다.
        if (limit != null && (limit < 1 || limit > 100)) {
            throw invalid("INVALID_COMPETITION_LIMIT", "조회 개수는 1부터 100까지 가능합니다.");
        }
        Competition.State filter = null;
        if (state != null) {
            filter = switch (state) {
                case "ready" -> Competition.State.READY;
                case "in_progress" -> Competition.State.IN_PROGRESS;
                case "completed" -> Competition.State.COMPLETED;
                case "cancelled" -> Competition.State.CANCELLED;
                default -> throw invalid("INVALID_COMPETITION_STATE", "지원하지 않는 대결 상태입니다.");
            };
        }
        var position = CompetitionListCursor.decode(cursor, filter);
        var now = tradingHours.now();
        var today = now.toLocalDate();
        // cursor와 limit이 모두 없으면 전체 조회다. cursor만 있으면 기본 10개씩 이어 읽는다.
        boolean paged = cursor != null || limit != null;
        int pageSize = limit == null ? 10 : limit;
        // 분할 조회는 한 개를 더 읽어 다음 페이지 유무를 확인한다. 전체 조회에는 개수 제한을 걸지 않는다.
        var fetched = competitions.findCursorPage(userId, filter,
                position == null ? null : position.getCreatedAt(),
                position == null ? null : position.getCompetitionId(),
                paged ? PageRequest.of(0, pageSize + 1) : Pageable.unpaged());
        boolean hasNext = paged && fetched.size() > pageSize;
        var rows = hasNext ? fetched.subList(0, pageSize) : fetched;
        String nextCursor = null;
        if (hasNext) {
            // 미리 읽은 한 개가 아닌, 실제 응답의 마지막 항목을 다음 조회 기준으로 삼는다.
            var last = rows.getLast();
            nextCursor = new CompetitionListCursor(last.getCreatedAt(), last.getId(), filter).encode();
        }
        var pagination = paged ? new CompetitionListResponse.Pagination(pageSize, hasNext, nextCursor) : null;
        long ongoingCount = competitions.countByUserIdAndState(userId, Competition.State.IN_PROGRESS);
        // 필터에 맞는 대결이 없어도 전체 진행 중 개수는 유지하고 계좌 조회를 생략한다.
        if (rows.isEmpty()) return new CompetitionListResponse(ongoingCount, java.util.List.of(), pagination);

        // 2. AI를 제외한 사용자 참가자와 계좌를 한 번에 조회한다.
        // stream()은 목록의 각 요소를 차례로 처리하는 기능이다. p는 현재 처리 중인 참가자다.
        // toMap(키, 값)으로 {대결 ID: 사용자 참가자}를 만든 뒤, 아래에서 {대결 ID: 계좌}로 바꾼다.
        // 참가자에는 완료 시 저장한 최종 수익률이 있고, 계좌에는 현재 자산이 있다.
        var competitionIds = rows.stream().map(Competition::getId).toList();
        var userParticipants = participants.findUserParticipantsInCompetitions(userId, competitionIds).stream()
                .collect(Collectors.toMap(p -> p.getCompetition().getId(), p -> p));
        var userAccounts = userParticipants.entrySet().stream()
                .collect(Collectors.toMap(java.util.Map.Entry::getKey, e -> e.getValue().getAccount()));

        // 3. 현재 수익률을 계산할 사용자 계좌만 추린다. c는 현재 처리 중인 대결이다.
        // filter: 진행 중이고 실제 거래 종료 시각(마지막 평일 15:30) 전인 대결만 남긴다.
        // map: 대결 객체를 그 대결의 사용자 계좌로 바꾼다.
        // nonNull: 연결된 계좌가 없는 항목을 제외한다. toList(): 처리 결과를 목록으로 만든다.
        // 종료 후 현재 시세로 계산하면 최종 성적이 계속 바뀌므로, 종료된 대결은 여기서 제외한다.
        var liveAccounts = rows.stream()
                .filter(c -> c.isTradingAt(now))
                .map(c -> userAccounts.get(c.getId())).filter(java.util.Objects::nonNull).toList();
        // 계좌 서비스가 현금 + 보유 주식 평가액으로 수익률을 계산한다.
        // returns는 Map<계좌 ID, 수익률>이다. userAccounts의 키인 '대결 ID'와 구분해야 한다.
        var returns = accountService.returnPercents(liveAccounts);

        // 4. 각 대결 Entity를 JSON 응답에 들어갈 CompetitionItem DTO로 변환한다.
        var items = rows.stream().map(c -> {
            boolean closed = c.getState() == Competition.State.COMPLETED || c.getState() == Competition.State.CANCELLED;
            // 조건 ? A : B는 조건이 참이면 A, 거짓이면 B를 선택한다.
            // 완료·취소는 null, 나머지는 종료일 - 오늘이다. Math.max(0, ...)으로 음수를 0으로 제한한다.
            // DAYS.between은 long을 반환하므로 Math.toIntExact로 DTO에 필요한 int로 변환한다.
            Integer dDay = closed ? null : Math.toIntExact(Math.max(0, ChronoUnit.DAYS.between(today, c.getEndDate())));

            // 대결 ID로 사용자 계좌를 찾고, 계좌 ID로 앞에서 계산한 수익률을 찾는다.
            // 계좌가 없거나 계산 대상에서 제외된 대결은 null이다. 시작 전 READY는 0%로 표시한다.
            var account = userAccounts.get(c.getId());
            Double returnPercent = account == null ? null : returns.get(account.getId());
            if (c.getState() == Competition.State.READY) returnPercent = 0.0;
            // 완료 대결은 현재 시세 대신 DB에 확정한 최종 수익률을 읽는다.
            var participant = userParticipants.get(c.getId());
            if (c.getState() == Competition.State.COMPLETED && participant != null
                    && participant.getFinalReturnPercent() != null) {
                returnPercent = participant.getFinalReturnPercent().doubleValue();
            }
            String winner = c.getWinner() == null ? null : c.getWinner().name().toLowerCase(Locale.ROOT);
            // Enum의 IN_PROGRESS, USER_VS_AI를 API 형식인 in_progress, user_vs_ai로 바꾼다.
            // 아직 정산하지 않은 대결의 winner는 null이다.
            return new CompetitionListResponse.CompetitionItem(c.getId(), c.getState().name().toLowerCase(Locale.ROOT),
                    c.getCompetitionName(), c.getCompetitionType().name().toLowerCase(Locale.ROOT),
                    c.getStartDate(), c.getEndDate(), dDay, returnPercent, winner);
        }).toList();

        // 5. 이번 페이지 항목·전체 진행 중 개수·다음 조회 정보를 함께 반환한다.
        return new CompetitionListResponse(ongoingCount, items, pagination);
    }

    @Transactional // 대결·참가 계좌·전략 중 하나라도 저장에 실패하면 모두 롤백한다.
    public CompetitionCreateResponse createCompetition(Long userId, CompetitionCreateRequest request) {
        CompetitionCreationLog.stage("request_validation");
        if (request == null || !validator.validate(request).isEmpty()) {
            throw invalid("INVALID_REQUEST", "요청 형식이 올바르지 않습니다.");
        }
        CompetitionCreationLog.stage("read_trading_time");
        var now = tradingHours.now(); // 자정 경계에서도 한 요청은 같은 날짜·시각으로 판단한다.
        CompetitionCreationLog.stage("user_lookup");
        var user = users.findLockedById(userId).orElseThrow(() -> new CompetitionException(
                HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "사용자를 찾을 수 없습니다."));
        CompetitionCreationLog.stage("validate_onboarding");
        if (!user.isOnboardingCompleted()) {
            throw new CompetitionException(HttpStatus.CONFLICT, "ONBOARDING_REQUIRED", "먼저 온보딩을 완료해주세요.");
        }
        // 단일 필드의 필수값·범위는 @Valid로 검사하고 여기서는 대결 정책을 검사한다.
        CompetitionCreationLog.stage("validate_competition_type");
        boolean aiVsAi = "ai_vs_ai".equals(request.getCompetitionType());
        if (!aiVsAi && !"user_vs_ai".equals(request.getCompetitionType())) {
            throw invalid("INVALID_COMPETITION_TYPE", "지원하지 않는 대결 유형입니다.");
        }
        CompetitionCreationLog.stage("validate_ai_participant_count");
        Integer count = request.getAiParticipantCount();
        if (count == null || (aiVsAi ? count < 2 || count > 4 : count != 1)) {
            throw invalid("INVALID_AI_PARTICIPANT_COUNT", "사용자 대 AI는 1명, AI 간 대결은 2~4명의 AI가 필요합니다.");
        }
        CompetitionCreationLog.stage("validate_agents");
        if (aiVsAi) {
            if (request.getAgents() == null) {
                throw invalid("INVALID_REQUEST", "AI 간 대결에는 에이전트 설정이 필요합니다.");
            }
            if (request.getAgents().size() != count) {
                throw invalid("AGENT_COUNT_MISMATCH", "대결의 AI 참가자 수와 설정 개수가 일치해야 합니다.");
            }
        } else if (request.getAgents() != null) {
            throw invalid("INVALID_REQUEST", "사용자 대 AI 대결에는 에이전트 설정을 지정할 수 없습니다.");
        }
        CompetitionCreationLog.stage("validate_start_date");
        if (!request.getStartDate().equals(now.toLocalDate())) {
            throw invalid("INVALID_START_DATE", "대결 시작일은 오늘 날짜여야 합니다.");
        }
        CompetitionCreationLog.stage("validate_period");
        if (ChronoUnit.DAYS.between(request.getStartDate(), request.getEndDate()) < 3) {
            throw invalid("INVALID_COMPETITION_PERIOD", "대결 기간은 최소 3일이어야 합니다.");
        }
        CompetitionCreationLog.stage("validate_competition_name");
        String name = request.getCompetitionName().trim();
        if (name.isBlank() || name.length() > 20) {
            throw invalid("INVALID_COMPETITION_NAME", "대결 이름은 공백이 아닌 20자 이하여야 합니다.");
        }
        CompetitionCreationLog.stage("check_account_name");
        if (accounts.existsByUserIdAndNameIgnoreCase(userId, name)
                || competitions.existsByUserIdAndCompetitionNameIgnoreCase(userId, name)) {
            throw new CompetitionException(HttpStatus.CONFLICT, "DUPLICATE_ACCOUNT_NAME", "이미 사용 중인 계좌 이름입니다.");
        }

        CompetitionCreationLog.stage("competition_save");
        var competition = competitions.save(new Competition(user, name,
                aiVsAi ? Competition.Type.AI_VS_AI : Competition.Type.USER_VS_AI,
                request.getStartDate(), request.getEndDate(), request.getInitialCapital(), count));
        CompetitionCreationLog.competitionId(competition.getId());
        if (aiVsAi) {
            agentCreation.createParticipants(competition, request.getAgents());
            CompetitionCreationLog.stage("transaction_commit");
            return new CompetitionCreateResponse("success", competition.getId(), "ready");
        }
        CompetitionCreationLog.stage("initialize_competition_state");
        if (tradingHours.isOpen(now)) competition.start(now.toInstant());

        // 두 계좌의 자금은 별개다. 사용자 계좌명만 대결명을 사용하고 AI 이름은 내부 식별용이다.
        CompetitionCreationLog.stage("user_account_save");
        var userAccount = accounts.save(new Account(user, name, request.getInitialCapital(), false));
        CompetitionCreationLog.accountId(userAccount.getId());
        CompetitionCreationLog.stage("ai_account_name");
        String aiName = nextAiAccountName(userId, competition.getId());
        CompetitionCreationLog.accountId(null);
        CompetitionCreationLog.stage("ai_account_save");
        var aiAccount = accounts.save(new Account(user, aiName, request.getInitialCapital(), true));
        CompetitionCreationLog.accountId(userAccount.getId());
        CompetitionCreationLog.stage("user_participant_save");
        participants.save(new CompetitionParticipant(competition, userAccount));
        CompetitionCreationLog.accountId(aiAccount.getId());
        CompetitionCreationLog.stage("ai_participant_save");
        participants.save(new CompetitionParticipant(competition, aiAccount));

        CompetitionCreationLog.accountId(null);
        CompetitionCreationLog.stage("transaction_commit");
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
