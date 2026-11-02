# 닉네임·프로필 이미지 변경

기준: 2026-11-02 API 명세서의 POST `/api/v1/users/me/profile-image/presigned-url`, PATCH `/api/v1/users/me`.
발급 응답은 명세대로 `presigned_url`, `profile_image_key` 두 필드다. 추가 `image_key` 또는 `expires_in` 필드는 반환하지 않는다.

## 프런트 호출 순서

1. 기존 로그인으로 받은 `access_token` 쿠키를 사용한다. BE 요청에는 `credentials: "include"`를 설정한다.
2. 기본 CSRF 활성화 환경에서는 GET `/api/v1/auth/csrf` 응답의 `token`을 `X-XSRF-TOKEN` 헤더에 넣는다. HttpOnly 쿠키를 JS로 읽지 않는다.
3. POST `/api/v1/users/me/profile-image/presigned-url`에 아래 JSON을 보낸다.

```json
{"content_type":"image/png","content_length":204800}
```

200 응답 예시:

```json
{"presigned_url":"https://bucket.s3.amazonaws.com/...?X-Amz-Signature=...","profile_image_key":"profiles/123/uuid.png"}
```

4. 받은 URL에 `PUT`으로 파일 자체를 보낸다. `FormData`로 감싸지 않는다. `Content-Type`은 발급 요청과 동일하게 지정하고 파일 길이도 같아야 한다. S3 요청에 BE 쿠키, JWT 헤더, CSRF 헤더를 보내지 않는다.
5. S3 업로드 성공 후 PATCH `/api/v1/users/me`를 호출한다.

```json
{"nickname":"스톡스푼","profile_image_key":"profiles/123/uuid.png"}
```

200 응답은 `{"message":"success"}`다. 닉네임만 보낼 수도 있고 이미지 키만 보낼 수도 있다.

| 필드 | 생략 | 문자열 | null |
|---|---|---|---|
| nickname | 유지 | 앞뒤 공백 제거 후 변경 | 400 |
| profile_image_key | 유지 | 해당 회원에게 발급한 이미지로 변경 | 이미지 삭제 |

빈 요청, 알 수 없는 필드, 잘못된 JSON 타입은 400이다. 닉네임은 현재 구현 정책상 1~50자이며 중복 금지를 추가하지 않았다. 이미지 삭제 후 GET은 `profile_image_url: null`을 반환하므로 FE가 기본 이미지를 표시한다.

## 저장과 조회

- 기존 DB `users.profile_image_url`을 재사용한다. Java 속성 이름은 `profileImageRef`다.
- 추가 테이블·컬럼·발급 이력 저장 없이 동작한다. DB 마이그레이션은 필요 없다.
- 가입 때는 카카오 URL을 저장한다. 이미지 변경 때는 서버만 쓸 수 있는 `profiles/final/{userId}/{uuid}.png` 또는 `.jpg` 키를 저장한다.
- 업로드용 URL이 가리키는 임시 키와 최종 저장 키를 분리한다. PATCH는 실제 파일의 크기·MIME·이미지 디코딩·픽셀 수를 검증한 바이트를 최종 키에 조건부 PUT한다. `If-None-Match: *`로 이미 생성된 최종 파일을 덮어쓰지 않는다.
- 같은 키로 PATCH를 재시도하면 기존 최종 파일을 재사용한다. 입력 키는 `profiles/{로그인한 회원 ID}/{UUID}.png` 또는 `.jpg` 형식인지 먼저 확인한다. 다른 회원 경로, 경로 이동, URL, 최종 키 직접 전달은 거부한다.
- 발급 이력을 별도로 저장하지 않으므로 발급 당시 요청값·시각과 다시 대조하지 않는다. 본인 경로에 이미 올라온 유효한 파일은 업로드 URL이 만료된 뒤에도 PATCH로 선택할 수 있다. URL 만료는 S3 업로드 권한의 만료이며 프로필 등록 기한이 아니다. 서버가 실제 S3 길이·MIME·내용과 상한을 확인한다.
- GET `/api/v1/users/me` 응답 이름은 기존 `profile_image_url`이다. 카카오 URL은 그대로, S3 키는 읽기용 서명 URL로 변환한다. 읽기 URL 만료 시 FE에서 GET을 다시 호출한다. 업로드 서명 URL을 이미지 표시 주소로 저장하지 않는다.
- 카카오 프로필은 신규 가입 때만 적용한다. 기존 회원은 카카오 정보를 바꾸어도 서비스의 닉네임·이미지가 자동 갱신되지 않는다. 삭제한 이미지도 재로그인으로 복구되지 않는다.
- S3 작업 후 DB 저장 실패 시 기존 프로필은 유지되며 최종 고아 파일은 정리 대상이다. S3와 DB를 단일 원자적 트랜잭션으로 보장하지 않는다. DB 잠금은 프로필을 반영하는 짧은 트랜잭션에서만 사용한다.
- 프로필 POST/PATCH는 DB 쓰기 재생 필터에서 제외한다. S3 상태 검증 없이 SQL만 나중에 재생하여 성공으로 응답하지 않는다.

## 현재 기본 정책 및 설정

API 명세에 없는 제한은 아래 구현 기본값이며 환경 설정으로 일부 조정할 수 있다.

| 환경 변수 | 기본값 | 의미 |
|---|---|---|
| PROFILE_IMAGE_BUCKET | 빈 문자열 | private S3 버킷. 비어 있으면 이미지 발급/등록/조회는 503, 기존 카카오 이미지 조회·닉네임 수정은 가능 |
| AWS_REGION | ap-northeast-2 | 버킷 리전 |
| PROFILE_IMAGE_MAX_BYTES | 5242880 | 5 MiB, 허용 설정 범위 1~10 MiB |
| PROFILE_IMAGE_UPLOAD_TTL_SECONDS | 300 | 업로드 URL 만료, 30~3600초 |
| PROFILE_IMAGE_READ_TTL_SECONDS | 900 | 조회 URL 만료, 30~3600초 |
| PROFILE_IMAGE_MAX_PIXELS | 20000000 | 이미지 총 픽셀 상한, 최대 20,000,000 |

현재 허용 형식은 **PNG/JPEG**다. SVG와 WebP는 받지 않는다. WebP는 Java 기본 디코더로 검증할 수 없어 초기 제안에서 제외했다. 이미지 최대 바이트, 픽셀 상한, 삭제 정책은 배포 전에 팀 정책과 맞춘다.

발급 요청의 길이를 PUT 서명에도 포함하며 PATCH에서 실제 바이트 수를 다시 확인한다. 실제 브라우저→S3 조합에서 서명된 길이/타입의 강제 여부는 로컬 테스트만으로 확인할 수 없으므로 배포 전 파일 길이·타입 변경 업로드를 직접 검증한다. 업로드 자체에 대한 크기 범위 강제가 필요하면 Presigned POST 정책 등 별도 계약 변경이 필요할 수 있다.

## 오류

| 상태 | code | 상황 |
|---|---|---|
| 400 | INVALID_REQUEST | 요청 형식, 닉네임, 발급 MIME/크기 오류 |
| 400 | INVALID_PROFILE_IMAGE | 타인 경로·잘못된 키 형식, 미업로드, 실제 파일 검증 실패 |
| 401 | UNAUTHORIZED | 인증 쿠키 누락·만료·삭제된 회원 |
| 403 | INVALID_CSRF_TOKEN | CSRF 활성화 환경에서 토큰 누락/불일치 |
| 404 | USER_NOT_FOUND | 인증 후 사용자 삭제 등으로 저장 시 회원이 없음 |
| 503 | PROFILE_IMAGE_UNAVAILABLE | 버킷 미설정, S3 권한·통신·자격증명 오류 |
| 503 | USER_STORAGE_UNAVAILABLE | 회원 DB 처리 실패 |

## 배포 준비

1. 기존 `users.profile_image_url`을 그대로 사용한다. 새 SQL 적용이나 ddl-auto 자동 갱신이 필요 없다. 운영 DB는 이 작업에서 직접 변경하지 않았다.
2. 서버 IAM 역할과 버킷/리전을 설정하고 백엔드를 배포한다. 키를 코드나 FE에 넣지 않는다.
3. FE를 연결하여 발급→PUT→PATCH→GET→재로그인을 확인한다.

서버 IAM에는 해당 버킷의 `profiles/*`에 대한 PutObject/GetObject와, 없는 최종 키의 HEAD가 404를 반환하도록 prefix 제한 ListBucket이 필요하다. 브라우저에 권한을 직접 주지 않으며, 버킷 전체 공개 읽기를 설정하지 않는다. 최종 키 쓰기는 백엔드만 허용한다.

S3 CORS 예시(Origin은 실제 FE 주소로 교체):

```json
[{"AllowedOrigins":["https://실제-FE-도메인"],"AllowedMethods":["PUT","GET","HEAD"],"AllowedHeaders":["content-type","x-amz-*"],"ExposeHeaders":["ETag"],"MaxAgeSeconds":300}]
```

임시 객체 lifecycle은 `profiles/` 아래 숫자 회원 ID prefix(또는 `upload=temporary` 태그 등 실제 운영 정책)에만 적용한다. **`profiles/` 전체 만료 규칙은 `profiles/final/`도 삭제하므로 금지한다.** 현재 API가 태그 헤더를 요구하지 않으므로 태그 방식 도입 시 별도 인프라 또는 서명 계약 변경이 필요하다. 운영 정리는 S3 객체 수정 시각과 실제 users 참조를 대조하여 수행한다.

교체·이미지 삭제·회원 탈퇴·DB 실패로 남은 최종 파일은 현재 자동 삭제하지 않는다. 활성 users.profile_image_url 참조를 확인한 후 정리한다. 발급 이력 테이블은 없다.

롤백할 때는 이전 앱이 S3 키를 그대로 URL로 반환하고 카카오 재로그인 때 덮어쓸 수 있으므로, S3 이미지가 저장된 뒤에는 호환 처리를 포함한 롤백 버전을 준비한다.

## 검증 범위

로컬 테스트는 실제 Spring Security·JPA/H2·트랜잭션·AWS 서명 생성과, SDK를 대체한 S3 응답으로 검증한다. 실제 AWS 버킷·IAM·브라우저 CORS 및 업로드는 별도 연동 확인이 필요하다. 이번 변경에서는 운영 DB/AWS 설정을 적용하지 않는다.
