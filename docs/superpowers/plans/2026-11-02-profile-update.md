# 닉네임·프로필 이미지 수정 구현 계획과 진행 기록

**최종 사용자 지시:** 기존 `users.profile_image_url` 컬럼만 사용한다. 추가 발급 이력 테이블 없이 사용자별 이미지 키 경로와 실제 S3 파일을 검증한다.

**작업 위치:** 현재 체크아웃된 브랜치에서 직접 진행하며 브랜치·worktree를 새로 만들거나 전환하지 않는다. 마지막 확인 브랜치는 `feat/75-profile-update`다. 커밋·푸시·운영 배포는 요청 범위에 포함하지 않는다.

**기술:** 기존 Java 21, Spring Boot 4.1.1, JPA, AWS SDK v2 BOM 2.55.6. S3 및 url-connection-client 모듈을 추가했다.

## API 계약

- 최신 API 명세 확인일: 2026-11-02.
- POST `/api/v1/users/me/profile-image/presigned-url`: content_type/content_length 요청, 200 presigned_url/profile_image_key 응답. 추가 응답 필드 없음.
- PATCH `/api/v1/users/me`: nickname/profile_image_key 선택 전달, 200 {"message":"success"} 응답.
- 이미지 필드 생략은 유지, 문자열은 교체, null은 삭제. 닉네임 생략은 유지, null·공백·50자 초과는 400.
- GET `/api/v1/users/me`의 응답 필드 이름 profile_image_url은 유지한다. S3 키는 조회용 서명 URL로 변환하고 기존 카카오 URL/null은 그대로 반환한다.
- 인증은 access_token 쿠키와 기본 활성화된 CSRF 검증을 사용한다. BE 요청에는 credentials: include를 설정한다.

## 구현 방식

1. 카카오 정보는 신규 가입 초기값으로 사용한다. 기존 사용자는 재로그인해도 DB의 닉네임·이미지를 유지한다.
2. DB 컬럼명과 타입은 바꾸지 않고 Java 속성만 profileImageRef로 정리한다. 카카오 URL, S3 최종 키 또는 null을 저장한다.
3. URL 발급은 회원 존재 확인 후 서버가 profiles/{userId}/{UUID}.png 또는 .jpg 키를 생성하고 S3 PUT URL을 서명한다. 별도 DB 쓰기·Redis 저장·발급 이력 테이블은 없다.
4. PATCH의 키는 본인 ID 경로와 UUID 파일명 형식인지 검증한다. 타인 경로, 임의 URL, 경로 이동, 최종 키 직접 전달은 거부한다.
5. 실제 S3 파일의 길이·MIME·디코딩·픽셀 수를 검증한다. 기본 제한은 PNG/JPEG, 5 MiB, 20,000,000픽셀이다. WebP는 기본 디코더로 검증할 수 없어 지원하지 않는다.
6. 검증한 바이트를 profiles/final/{userId}/{UUID} 키에 조건부 PUT한다. If-None-Match: *로 최종 파일 덮어쓰기를 차단한다. 기존 최종 파일이 있으면 재사용한다.
7. S3 처리는 DB 쓰기 트랜잭션 밖에서 수행하고, 마지막에 회원을 잠금 재조회하여 요청된 필드만 원자적으로 저장한다. 실패 시 기존 프로필은 유지한다.
8. 발급 이력 대조는 하지 않는다. 본인 경로의 유효한 기존 파일은 업로드 URL 만료 후에도 선택할 수 있다. URL 만료는 업로드 권한에만 적용된다.
9. S3와 DB를 함께 사용하는 프로필 요청은 DB 쓰기 재생 필터에서 제외한다.

## 단계별 상태

- [x] 1단계: 기존 이미지 컬럼 재사용, 개별 변경 메서드, 카카오 재로그인 시 프로필 유지.
- [x] 2단계: S3 설정, Presigned URL 발급, 사용자별 키/파일 검증, 최종 파일 불변성.
- [x] 3단계: PATCH와 GET URL 변환, 쿠키/CSRF, 오류 응답.
- [x] 4단계: API·파일 검증·재로그인·CORS·롤백·동시 수정·탈퇴 경합 테스트.
- [x] 사용자 수정 지시: 발급 이력 엔티티·저장소·테이블 생성 SQL·탈퇴 연결 제거, 테스트/운영 문서 정리.
- [x] 수정 후 전체 테스트·빌드·SpotBugs 최종 결과 기록.
- [ ] 실제 버킷/IAM/CORS 설정과 브라우저 업로드 확인(운영 환경 작업).

## 검증 기록

- 기존 재로그인 덮어쓰기 테스트 2건이 실패하는 것을 먼저 확인한 뒤 수정하여 통과했다.
- PATCH 미구현 상태에서 보존·삭제·입력 검증 테스트 실패를 확인한 뒤 구현하여 통과했다.
- 프로필 요청의 쓰기 재생 제외 테스트도 실패 후 수정하여 통과했다.
- 최초 구현 전체 build/spotbugsMain 통과: 테스트 616개 중 598개 통과, 18개 건너뜀, 실패 0.
- 독립 코드 검토에서 확인된 제품 결함은 없었으며 트랜잭션 검증 부족을 보완했다. 실제 flush 후 커밋 실패 롤백, 동시 필드 수정, 이미지 준비 중 탈퇴 테스트가 통과했다. JPEG·픽셀 상한·스트림 길이·조건부 PUT 충돌 검증도 보완했다.
- 발급 이력 제거 전, 발급 기록 없이 본인 경로의 S3 이미지를 등록하는 테스트가 실패하는 것을 확인했다. 이후 테이블 의존성을 제거했다.
- 수정 후 `.\gradlew.bat build spotbugsMain` BUILD SUCCESSFUL(1분 47초), 전체 테스트·JAR 빌드·SpotBugs 통과. `git diff --check` 통과. 실제 DB와 AWS는 변경하지 않았다.

## 배포와 제한

- DB 스키마 변경 및 새 SQL 적용은 없다. 최초 구현에 포함했던 db/user-profile.sql은 삭제했다. 실제 DB에 적용된 적이 없으므로 DROP SQL도 필요 없다.
- 버킷명/리전, 서버 IAM, FE origin에 대한 S3 PUT CORS가 필요하다. AWS 자격증명은 서버 기본 체인을 사용하며 FE에 전달하지 않는다.
- 기본 업로드 TTL 300초, 조회 TTL 900초. 길이·만료 시간·픽셀 상한은 설정으로 조정한다.
- 실제 S3 서명 헤더의 브라우저 강제 동작, IAM, CORS, MySQL 운영 환경은 아직 검증하지 않았다. 로컬 검증은 실제 Spring/JPA/H2와 모의 S3 및 실제 서명 생성으로 수행한다.
- 교체·삭제·탈퇴 또는 DB 실패로 생긴 S3 고아 파일 자동 정리는 별도 범위다. 실제 users 참조를 확인해 운영 정리한다. profiles/ 전체 만료 규칙은 profiles/final/도 삭제하므로 적용하지 않는다.
- 자세한 연동/오류/설정은 docs/user-profile-update.md에 기록한다.
