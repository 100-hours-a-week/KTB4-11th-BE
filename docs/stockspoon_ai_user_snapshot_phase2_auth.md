# AI 서버용 전체 사용자 조회: 2단계 인증

## 확정된 요청 방식

AI 서버는 `GET /api/v1/users/ai-server` 요청에 `access_token` 쿠키를 보낸다.
토큰은 BE와 공유한 JWT 시크릿으로 서명하고, BE의 `JWT_ISSUER`와 같은 `iss`,
`sub=ai-server`, `type=access`, `actor=AI`, 유효한 `iat`와 `exp`를 담는다.
토큰 원문과 시크릿은 문서·로그에 기록하지 않는다.

이 토큰은 전체 사용자 조회에 사용한다. 사용자별 주문 API는 기존 계약대로
`sub=사용자 ID`, `actor=AI`인 JWT로 계좌 소유자를 확인한다.

## BE의 처리

기존 `SecurityConfig`는 `access_token` 쿠키에서 토큰을 읽고, `JwtConfig`의 access
디코더는 서명, 발급자, 만료 시각, `type=access`를 검증한다. 이 단계에서는
`GET /api/v1/users/ai-server`에 `actor=AI` 및 `sub=ai-server` 조건을 추가했다.
인증이 없는 요청이나 잘못된 종류·서명의 토큰은 401, 검증된 토큰이지만 AI 조회
클레임이 맞지 않는 요청은 403이다.
또한 `sub=ai-server`인 서비스 토큰으로 다른 보호 API를 요청하면 403으로 거절한다.
기존 사용자 토큰(`sub=사용자 ID`)의 보호 API 접근 방식은 유지한다.

HTTP GET 메서드와 최종 응답은 아직 구현하지 않았다. 현재가 연결과 최종 JSON 변환이
끝나기 전까지 성공 응답을 노출하지 않기 위해서다.

## 확인

`SecurityJwtTests`에서 쿠키 없음, Bearer 헤더만 사용, 일반 사용자 access 토큰,
`sub=사용자 ID`인 AI access 토큰, refresh 토큰을 거절하는지 확인했다.
올바른 AI 조회용 쿠키는 인증·인가를 통과하지만 현재 컨트롤러가 없어서 404가 된다.
서비스 토큰의 다른 보호 API 접근은 403, 토큰 없는 보호 API 접근은 401인지도 확인했다.
최종 GET을 연결할 때 이 404 검증을 실제 성공 응답 검증으로 교체해야 한다.
