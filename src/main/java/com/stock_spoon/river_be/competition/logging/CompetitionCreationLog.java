package com.stock_spoon.river_be.competition.logging;

import com.stock_spoon.river_be.competition.exception.CompetitionException;
import java.sql.SQLException;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/** 생성 요청 스레드의 실패 위치만 추적한다. 요청 종료 시 이전 MDC를 복원한다. */
public final class CompetitionCreationLog implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(CompetitionCreationLog.class);
    private static final String STAGE = "competitionCreateStage";
    private static final String USER = "competitionCreateUserId";
    private static final String COMPETITION = "competitionCreateId";
    private static final String ACCOUNT = "competitionCreateAccountId";
    private final Map<String, String> previous = new LinkedHashMap<>();

    public CompetitionCreationLog() {
        for (String key : List.of(STAGE, USER, COMPETITION, ACCOUNT)) {
            previous.put(key, MDC.get(key));
            MDC.remove(key);
        }
        MDC.put(STAGE, "identity_resolution");
    }

    // API 경계에서 범위를 연 경우에만 기록해 직접 서비스 호출에는 MDC를 남기지 않는다.
    public static void stage(String stage) { if (MDC.get(STAGE) != null) MDC.put(STAGE, stage); }
    public static void userId(Long id) { put(USER, id); }
    public static void competitionId(Long id) { put(COMPETITION, id); }
    public static void accountId(Long id) { put(ACCOUNT, id); }
    private static void put(String key, Long id) {
        if (MDC.get(STAGE) != null) {
            if (id == null) MDC.remove(key);
            else MDC.put(key, String.valueOf(id));
        }
    }

    public void failure(RuntimeException error) {
        String code = error instanceof CompetitionException expected ? expected.getCode()
                : error instanceof org.springframework.dao.DataAccessException ? "DATABASE_ERROR" : "UNEXPECTED_ERROR";
        int status = error instanceof CompetitionException expected ? expected.getStatus().value() : 500;
        write(error, status, code, "none");
    }

    public void invalidRequest(Exception error, String fields) {
        write(error, 400, "INVALID_REQUEST", fields);
    }

    private void write(Throwable error, int status, String code, String fields) {
        Throwable root = error;
        SQLException sql = null;
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = error; cause != null && visited.add(cause); cause = cause.getCause()) {
            root = cause;
            if (cause instanceof SQLException found) sql = found;
        }
        var frame = root.getStackTrace().length == 0 ? null : root.getStackTrace()[0];
        String origin = frame == null ? "none" : frame.getClassName() + "#" + frame.getMethodName() + ":" + frame.getLineNumber();
        String sqlState = sql == null || sql.getSQLState() == null || !sql.getSQLState().matches("[A-Z0-9]{5}")
                ? "none" : sql.getSQLState();
        if (sql != null && "UNEXPECTED_ERROR".equals(code)) code = "DATABASE_ERROR";
        var level = status >= 500 ? log.atError() : log.atWarn();
        // 메시지·Throwable 원문에는 요청 값이나 SQL 값이 들어갈 수 있으므로 유형과 코드 위치만 기록한다.
        level.log("event=competition_creation_failed stage={} code={} status={} userId={} competitionId={} accountId={} "
                        + "fieldIssues={} causeType={} rootCauseType={} origin={} sqlState={} sqlErrorCode={}",
                value(STAGE), code, status, value(USER), value(COMPETITION), value(ACCOUNT), fields,
                error.getClass().getSimpleName(), root.getClass().getSimpleName(), origin, sqlState,
                sql == null ? 0 : sql.getErrorCode());
    }

    private static String value(String key) { return MDC.get(key) == null ? "none" : MDC.get(key); }

    @Override
    public void close() {
        previous.forEach((key, value) -> {
            if (value == null) MDC.remove(key);
            else MDC.put(key, value);
        });
    }
}
