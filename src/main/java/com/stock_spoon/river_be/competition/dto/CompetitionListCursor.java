package com.stock_spoon.river_be.competition.dto;

import com.stock_spoon.river_be.competition.entity.Competition;
import com.stock_spoon.river_be.competition.exception.CompetitionException;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Getter
@AllArgsConstructor
public class CompetitionListCursor {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int MAX_CURSOR_LENGTH = 512;

    // 생성일이 같아도 ID를 함께 비교하면 다음 조회 위치를 하나로 정할 수 있다.
    private final Instant createdAt;
    private final long competitionId;
    private final Competition.State state;

    public String encode() {
        var payload = JSON.createObjectNode();
        // 문자열로 보관하여 DB에서 읽은 시각의 소수점 정밀도를 그대로 유지한다.
        payload.put("created_at", createdAt.toString());
        payload.put("competition_id", competitionId);
        if (state == null) payload.putNull("state");
        else payload.put("state", state.name().toLowerCase(Locale.ROOT));
        // URL에 넣기 쉬운 표현으로 변환한다. 암호화나 인증 수단은 아니다.
        return Base64.getUrlEncoder().withoutPadding().encodeToString(JSON.writeValueAsBytes(payload));
    }

    public static CompetitionListCursor decode(String cursor, Competition.State expectedState) {
        // 첫 페이지 요청에는 이전 조회 위치가 없다.
        if (cursor == null) return null;
        if (cursor.isEmpty() || cursor.length() > MAX_CURSOR_LENGTH || !cursor.matches("[A-Za-z0-9_-]+")) {
            throw invalid();
        }
        try {
            var payload = JSON.readTree(Base64.getUrlDecoder().decode(cursor));
            if (payload == null || !payload.isObject() || payload.size() != 3) throw invalid();
            var date = payload.get("created_at");
            var id = payload.get("competition_id");
            var filter = payload.get("state");
            if (date == null || !date.isTextual() || id == null || !id.isIntegralNumber()
                    || !id.canConvertToLong() || id.longValue() <= 0 || filter == null
                    || (!filter.isNull() && !filter.isTextual())) throw invalid();
            Instant createdAt = Instant.parse(date.asText());
            Competition.State state = filter.isNull() ? null
                    : Competition.State.valueOf(filter.asText().toUpperCase(Locale.ROOT));
            // 전체 목록에서 받은 cursor를 진행 중 목록에 재사용하지 못하게 한다.
            if (state != expectedState) throw invalid();
            return new CompetitionListCursor(createdAt, id.longValue(), state);
        } catch (IllegalArgumentException | DateTimeException | JacksonException error) {
            // 파싱 오류 원문에 사용자 입력이 포함될 수 있어 응답에는 고정 메시지만 사용한다.
            throw invalid();
        }
    }

    private static CompetitionException invalid() {
        return new CompetitionException(HttpStatus.BAD_REQUEST, "INVALID_COMPETITION_CURSOR", "대결 목록 커서가 올바르지 않습니다.");
    }
}
