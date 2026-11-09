# 닉네임·프로필 이미지 변경

기준: 2026-11-02 API 명세서의 POST `/api/v1/users/me/profile-image/presigned-url`, PATCH `/api/v1/users/me`.
발급 응답은 명세대로 `presigned_url`, `profile_image_key` 두 필드다. 추가 `image_key` 또는 `expires_in` 필드는 반환하지 않는다.

현재 구현 기준은 `refactor/266-profile-image-s3-config`의 2026-11-09 인프라 규격 반영 작업이다. 초기 프로필 구현과 후속 변경을 함께 이 브랜치로 이동했다. 아래 객체 경로는 기본 `PROFILE_IMAGE_S3_KEY_PREFIX=profile-images/` 기준이다.

## 백엔드 호출 흐름

공통 패키지 루트는 `com.stock_spoon.river_be`다. 아래 클래스 앞의 `user.controller`, `user.service` 등은 이 루트 아래 실제 패키지다.

```text
POST /api/v1/users/me/profile-image/presigned-url
  → user.controller.UserController.issueImageUrl(jwt, body)
    → Long.parseLong(jwt.getSubject()) → userId
    → user.dto.ProfileImageUploadRequest.parse(body) → request
      → JSON 필드·타입 검사 실패: 400 INVALID_REQUEST
    → user.service.ProfileImageService.issue(userId, request)
      → PNG/JPEG 및 contentLength 범위 검사
      → user.repository.UserRepository.existsById(userId)
      → configured(): 버킷 설정 확인
      → temporaryPrefix(userId) + UUID + 확장자 → key
      → S3Presigner.presignPutObject(PutObjectPresignRequest)
        → bucket/key/contentType/contentLength 및 uploadTtlSeconds로 서명
      → user.dto.ProfileImageUploadResponse(url, key)
  → 200 {presigned_url, profile_image_key}

FE → 반환된 presigned_url로 S3 PUT(파일 바이트)
  → 업로드 성공 후 아래 PATCH 호출

PATCH /api/v1/users/me
  → user.controller.UserController.updateMe(jwt, body)
    → Long.parseLong(jwt.getSubject()) → userId
    → user.dto.UserProfileUpdateRequest.parse(body) → request
      → 허용 필드·타입 검사, nickname.strip(), 필드 존재 여부 보존
    → user.service.UserService.updateMe(userId, request)
      → 이미지 문자열이 있으면 ProfileImageService.prepare(userId, request.imageKey())
        → temporaryPrefix(userId)와 UUID 파일명 검사
        → S3Client.getObject(GetObjectRequest(bucket, key))
        → 실제 길이·Content-Type 검사 및 제한된 바이트 읽기
        → validateImage(bytes, contentType): 디코딩·형식·픽셀 수 검사
        → S3Client.putObject(PutObjectRequest(bucket, finalKey, If-None-Match=*), bytes)
          → 412이면 기존 최종 객체 유지, 다른 오류는 실패
        → finalKey 반환
      → TransactionTemplate.executeWithoutResult(status -> ...)
        → UserRepository.findLockedById(userId): PESSIMISTIC_WRITE 잠금
        → nickname 존재: user.entity.User.updateNickname(request.nickname())
        → 이미지 존재: clearProfileImage() 또는 replaceProfileImage(finalKey)
        → JPA 변경 감지로 users 갱신·커밋
  → 200 {"message":"success"}

GET /api/v1/users/me
  → user.controller.UserController.getMe(jwt)
    → user.service.UserService.getMe(Long.parseLong(jwt.getSubject()))
      → UserRepository.findById(userId)
      → ProfileImageService.resolveUrl(user.getProfileImageRef())
        → null/외부 URL: 그대로 반환
        → 설정된 최종 키 prefix: bucket/publicBaseUrl 확인
          → publicBaseUrl + ref에서 keyPrefix를 제외한 경로
      → user.dto.UserMeResponse(nickname, profileImageUrl, onboardingCompleted)
  → 200 {nickname, profile_image_url, onboarding_completed}
```

### 실행 순서 설명

요청이 컨트롤러에 도달하기 전에 [SecurityConfig](../src/main/java/com/stock_spoon/river_be/config/SecurityConfig.java)의 `accessTokenResolver()`가 `access_token` 쿠키를 읽고, `securityFilterChain(...)`에 구성한 Resource Server가 JWT를 검증한다. 사용자 존재 확인 필터와 접근 권한 검사도 거친다. POST/PATCH는 기본 활성화된 CSRF 검증 대상이다. 인증 완료된 JWT를 `@AuthenticationPrincipal`로 주입하는 과정과, 본문을 `JsonNode body`로 변환하는 과정은 별개다. 이 API는 `@Valid` 대신 각 요청 DTO의 `parse(body)`에서 직접 입력을 검사한다.

URL 발급 요청은 `user.controller` 패키지의 [UserController.issueImageUrl(jwt, body)](../src/main/java/com/stock_spoon/river_be/user/controller/UserController.java)에 들어온다. 컨트롤러는 JWT subject를 `long userId`로 변환하고, [ProfileImageUploadRequest.parse(body)](../src/main/java/com/stock_spoon/river_be/user/dto/ProfileImageUploadRequest.java)로 `content_type`과 바이트 단위 `content_length`를 받는다. 이어 [ProfileImageService.issue(userId, request)](../src/main/java/com/stock_spoon/river_be/user/service/ProfileImageService.java)는 MIME·파일 크기를 검사하고 `UserRepository.existsById(userId)`로 사용자가 남아 있는지 확인한다. 버킷이 설정되어 있으면 사용자 ID와 UUID로 임시 키를 생성하고, 타입·길이·만료 시간을 넣어 AWS SDK의 `presignPutObject(...)`를 호출한다. S3에서 URL 발급 응답을 받아오는 별도 HTTP 요청이 아니라 BE가 AWS 자격 증명으로 URL에 서명하는 과정이다. 이때 이미지 파일이나 DB 프로필은 저장하지 않으며, 컨트롤러가 [ProfileImageUploadResponse](../src/main/java/com/stock_spoon/river_be/user/dto/ProfileImageUploadResponse.java)를 200으로 반환한다.

FE가 S3에 직접 업로드한 뒤 PATCH를 보내면 `UserController.updateMe(jwt, body)`가 호출된다. [UserProfileUpdateRequest.parse(body)](../src/main/java/com/stock_spoon/river_be/user/dto/UserProfileUpdateRequest.java)는 필드가 생략됐는지를 `hasNickname`·`hasImage`에 보존한다. 닉네임은 앞뒤 공백을 제거하고, 이미지 키는 문자열 또는 null인지 확인한다. 이어 [UserService.updateMe(userId, request)](../src/main/java/com/stock_spoon/river_be/user/service/UserService.java)가 이미지 문자열이 있는 경우에만 `ProfileImageService.prepare(userId, request.imageKey())`를 호출한다. `prepare(...)`는 본인 경로와 UUID 형식을 검사하고 S3의 임시 파일을 읽는다. 헤더의 길이·타입과 실제 바이트를 확인한 뒤 `validateImage(bytes, contentType)`에서 이미지 디코딩과 총 픽셀 수까지 검사한다. 검증한 바이트를 최종 키에 조건부 PUT하고, 이미 최종 객체가 있어 412가 오면 덮어쓰지 않은 채 동일한 최종 키를 반환한다.

S3 읽기·검증·쓰기 완료 후에만 `UserService.updateMe(...)`가 `TransactionTemplate.executeWithoutResult(...)`로 DB 트랜잭션을 시작한다. [UserRepository.findLockedById(userId)](../src/main/java/com/stock_spoon/river_be/user/repository/UserRepository.java)는 해당 회원 행에 쓰기 잠금을 걸어 동시 수정을 순서대로 처리한다. 서비스는 요청에 포함된 필드에만 [User.updateNickname(...), replaceProfileImage(...), clearProfileImage()](../src/main/java/com/stock_spoon/river_be/user/entity/User.java)를 적용한다. 별도의 `save()` 호출 없이 관리 중인 엔티티의 변경 감지로 커밋하며, 컨트롤러는 서비스가 정상 종료한 뒤 성공 JSON을 반환한다. DB 실패 시 두 필드의 변경은 롤백되지만 이미 저장한 S3 파일은 남을 수 있다. 이미지 준비 중 회원이 탈퇴했다면 잠금 재조회에서 404가 발생하여 사용자를 다시 생성하지 않는다. 이 두 쓰기 API는 [DbWriteReplayRequestFilter](../src/main/java/com/stock_spoon/river_be/config/DbWriteReplayRequestFilter.java)의 재생 대상에서 제외한다.

조회 요청은 `UserController.getMe(jwt)`에서 `UserService.getMe(userId)`로 전달된다. 읽기 전용 트랜잭션에서 `UserRepository.findById(userId)`로 회원을 조회하고, `ProfileImageService.resolveUrl(ref)`에 저장된 `profileImageRef`를 전달한다. 최종 객체 키라면 설정한 공개 URL로 바꾸고, 카카오 URL과 null은 그대로 둔다. 서비스가 [UserMeResponse](../src/main/java/com/stock_spoon/river_be/user/dto/UserMeResponse.java)를 구성하여 컨트롤러를 통해 200으로 반환한다. 이 조회 URL 변환에는 S3 객체 조회나 GET 서명이 없다.

### 단계별 전달값

| 단계 | 메서드 | 입력 출처·전달값 | 처리·반환값 |
|---|---|---|---|
| 발급 입력 | `UserController.issueImageUrl` → `ProfileImageUploadRequest.parse` | JWT subject, JSON의 `content_type`·`content_length` | `long userId`, `ProfileImageUploadRequest` |
| 서명 | `ProfileImageService.issue` | 사용자 ID, MIME, 바이트 길이 | `ProfileImageUploadResponse(presignedUrl, profileImageKey)` |
| 수정 입력 | `UserProfileUpdateRequest.parse` | JSON의 닉네임·이미지 키와 각 필드 존재 여부 | 정규화한 닉네임, 키/null, `hasNickname`·`hasImage` |
| 파일 확정 | `ProfileImageService.prepare` | 인증 사용자 ID, 임시 객체 키 | 검증된 이미지의 최종 객체 키 `finalKey` |
| DB 반영 | `UserService.updateMe` → `UserRepository.findLockedById` | 사용자 ID, 요청 DTO, `finalKey` | 요청된 필드만 수정하고 커밋 |
| 조회 변환 | `ProfileImageService.resolveUrl` | DB `users.profile_image_url`의 참조값 | 카카오 URL/null 또는 공개 S3 URL |

## 정책·변환 예시

사용자 ID가 123이고 파일명이 `12345678-1234-4234-8234-123456789abc.png`이면 발급 키는 `profile-images/123/12345678-1234-4234-8234-123456789abc.png`, DB 저장 키는 `profile-images/final/123/12345678-1234-4234-8234-123456789abc.png`다. 조회 때는 `publicBaseUrl`에 `final/123/12345678-1234-4234-8234-123456789abc.png`를 붙인다. `profile-images/`를 두 번 붙이지 않는다. 설정값의 끝 `/` 보정은 [ProfileImageProperties](../src/main/java/com/stock_spoon/river_be/config/ProfileImageProperties.java)의 생성자에서 수행한다.

`{"nickname":" 새 이름 "}`은 닉네임만 `새 이름`으로 바꾸고, `{"profile_image_key":null}`은 이미지만 지운다. 두 필드를 함께 전달하면 이미지 검증 실패 시 닉네임도 변경하지 않는다. 같은 키의 PATCH 재시도도 임시 파일을 다시 읽고 검증하므로 임시 객체가 삭제되었으면 기존 최종 객체가 있어도 재시도가 실패한다.

## 프런트 호출 순서

1. 기존 로그인으로 받은 `access_token` 쿠키를 사용한다. BE 요청에는 `credentials: "include"`를 설정한다.
2. 기본 CSRF 활성화 환경에서는 GET `/api/v1/auth/csrf` 응답의 `token`을 `X-XSRF-TOKEN` 헤더에 넣는다. HttpOnly 쿠키를 JS로 읽지 않는다.
3. POST `/api/v1/users/me/profile-image/presigned-url`에 아래 JSON을 보낸다.

```json
{"content_type":"image/png","content_length":204800}
```

200 응답 예시:

```json
{"presigned_url":"https://bucket.s3.amazonaws.com/...?X-Amz-Signature=...","profile_image_key":"profile-images/123/uuid.png"}
```

4. 받은 URL에 `PUT`으로 파일 자체를 보낸다. `FormData`로 감싸지 않는다. `Content-Type`은 발급 요청과 동일하게 지정하고 파일 길이도 같아야 한다. S3 요청에 BE 쿠키, JWT 헤더, CSRF 헤더를 보내지 않는다.
5. S3 업로드 성공 후 PATCH `/api/v1/users/me`를 호출한다.

```json
{"nickname":"스톡스푼","profile_image_key":"profile-images/123/uuid.png"}
```

200 응답은 `{"message":"success"}`다. 닉네임만 보낼 수도 있고 이미지 키만 보낼 수도 있다.

| 필드 | 생략 | 문자열 | null |
|---|---|---|---|
| nickname | 유지 | 앞뒤 공백 제거 후 변경 | 400 |
| profile_image_key | 유지 | 본인 경로의 유효한 업로드 이미지로 변경 | 이미지 삭제 |

빈 요청, 알 수 없는 필드, 잘못된 JSON 타입은 400이다. 닉네임은 현재 구현 정책상 1~50자이며 중복 금지를 추가하지 않았다. 이미지 삭제 후 GET은 `profile_image_url: null`을 반환하므로 FE가 기본 이미지를 표시한다.

## 저장과 조회

- 기존 DB `users.profile_image_url`을 재사용한다. Java 속성 이름은 `profileImageRef`다.
- 추가 테이블·컬럼·발급 이력 저장 없이 동작한다. DB 마이그레이션은 필요 없다.
- 가입 때는 카카오 URL을 저장한다. 이미지 변경 때는 `profile-images/final/{userId}/{uuid}.png` 또는 `.jpg` 키를 저장한다.
- 업로드용 URL이 가리키는 임시 키와 최종 저장 키를 분리한다. PATCH는 실제 파일의 크기·MIME·이미지 디코딩·픽셀 수를 검증한 바이트를 최종 키에 조건부 PUT한다. `If-None-Match: *`로 이미 생성된 최종 파일을 덮어쓰지 않는다.
- 입력 키는 `profile-images/{로그인한 회원 ID}/{UUID}.png` 또는 `.jpg` 형식인지 먼저 확인한다. 다른 회원 경로, 경로 이동, URL, 최종 키 직접 전달은 거부한다.
- 발급 이력을 별도로 저장하지 않으므로 발급 당시 요청값·시각과 다시 대조하지 않는다. 본인 경로에 이미 올라온 유효한 파일은 업로드 URL이 만료된 뒤에도 PATCH로 선택할 수 있다. URL 만료는 S3 업로드 권한의 만료이며 프로필 등록 기한이 아니다. 서버가 실제 S3 길이·MIME·내용과 상한을 확인한다.
- GET `/api/v1/users/me` 응답 이름은 기존 `profile_image_url`이다. 카카오 URL은 그대로, S3 키는 `PROFILE_IMAGE_S3_PUBLIC_BASE_URL`에 키 prefix를 제외한 경로를 붙인 공개 URL로 변환한다. 업로드 서명 URL을 이미지 표시 주소로 저장하지 않는다.
- 카카오 프로필은 신규 가입 때만 적용한다. 기존 회원은 카카오 정보를 바꾸어도 서비스의 닉네임·이미지가 자동 갱신되지 않는다. 삭제한 이미지도 재로그인으로 복구되지 않는다.
- S3 작업 후 DB 저장 실패 시 기존 프로필은 유지되며 최종 고아 파일은 정리 대상이다. S3와 DB를 단일 원자적 트랜잭션으로 보장하지 않는다. DB 잠금은 프로필을 반영하는 짧은 트랜잭션에서만 사용한다.
- 프로필 POST/PATCH는 DB 쓰기 재생 필터에서 제외한다. S3 상태 검증 없이 SQL만 나중에 재생하여 성공으로 응답하지 않는다.

## 현재 기본 정책 및 설정

API 명세에 없는 제한은 아래 구현 기본값이며 환경 설정으로 일부 조정할 수 있다.

| 환경 변수 | 기본값 | 의미 |
|---|---|---|
| PROFILE_IMAGE_S3_BUCKET | 빈 문자열 | 프로필 이미지 S3 버킷. 비어 있으면 이미지 발급/등록은 503, 기존 카카오 이미지 조회·닉네임 수정은 가능 |
| PROFILE_IMAGE_S3_KEY_PREFIX | profile-images/ | S3 객체 키 prefix. 끝 `/`는 생략해도 된다 |
| PROFILE_IMAGE_S3_PUBLIC_BASE_URL | 빈 문자열 | `profile-images/` 아래 객체를 노출하는 공개 URL prefix. 끝 `/`는 생략해도 된다 |
| AWS_REGION | ap-northeast-2 | 버킷 리전 |
| PROFILE_IMAGE_MAX_BYTES | 5242880 | 5 MiB, 허용 설정 범위 1~10 MiB |
| PROFILE_IMAGE_UPLOAD_TTL_SECONDS | 300 | 업로드 URL 만료, 30~3600초 |
| PROFILE_IMAGE_MAX_PIXELS | 20000000 | 이미지 총 픽셀 상한, 최대 20,000,000 |

현재 허용 형식은 **PNG/JPEG**다. SVG와 WebP는 받지 않는다. WebP는 Java 기본 디코더로 검증할 수 없어 초기 제안에서 제외했다. 이미지 최대 바이트, 픽셀 상한, 삭제 정책은 배포 전에 팀 정책과 맞춘다.

발급 요청의 길이를 PUT 서명에도 포함하며 PATCH에서 실제 바이트 수를 다시 확인한다. 실제 브라우저→S3 조합에서 서명된 길이/타입의 강제 여부는 로컬 테스트만으로 확인할 수 없으므로 배포 전 파일 길이·타입 변경 업로드를 직접 검증한다. 업로드 자체에 대한 크기 범위 강제가 필요하면 Presigned POST 정책 등 별도 계약 변경이 필요할 수 있다.

## 오류

입력·이미지 오류는 [ProfileUpdateException](../src/main/java/com/stock_spoon/river_be/user/exception/ProfileUpdateException.java)으로 발생하고 [UserExceptionHandler.handle(...)](../src/main/java/com/stock_spoon/river_be/user/exception/UserExceptionHandler.java)이 해당 상태와 `{code, message}`로 변환한다. 회원 조회 실패도 같은 핸들러의 `handle(UserNotFoundException)`이 404로 변환한다. DB 접근·커밋 예외는 `UserController.profileDatabaseUnavailable(error)`가 503으로 반환한다. 인증·CSRF 오류는 컨트롤러 진입 전 SecurityConfig에서 응답한다.

| 상태 | code | 상황 |
|---|---|---|
| 400 | INVALID_REQUEST | 요청 형식, 닉네임, 발급 MIME/크기 오류 |
| 400 | INVALID_PROFILE_IMAGE | `parse`의 키 타입/길이 오류, `prepare`의 본인 경로·파일명 오류, S3 404, 실제 파일 검증 실패 |
| 401 | UNAUTHORIZED | 인증 쿠키 누락·만료·삭제된 회원 |
| 403 | INVALID_CSRF_TOKEN | CSRF 활성화 환경에서 토큰 누락/불일치 |
| 404 | USER_NOT_FOUND | 인증 후 사용자 삭제 등으로 저장 시 회원이 없음 |
| 503 | PROFILE_IMAGE_UNAVAILABLE | 버킷 미설정, 최종 키 조회 시 공개 base URL 미설정, S3 권한·통신·자격증명 오류 |
| 503 | USER_STORAGE_UNAVAILABLE | 회원 DB 처리 실패 |

`prepare(...)`는 S3 404를 400으로 변환하지만 403은 503으로 변환한다. 전달받은 역할에는 ListBucket이 없으므로 존재하지 않는 임시 객체가 S3에서 403으로 처리되면 미업로드 요청도 503이 될 수 있다. 실제 AWS 응답은 연동 확인 대상이다. 최종 객체 조건부 PUT의 412만 정상 재시도로 허용하고, 나머지 S3 오류는 위 규칙에 따라 실패한다.

## 배포 준비

1. 기존 `users.profile_image_url`을 그대로 사용한다. 새 SQL 적용이나 ddl-auto 자동 갱신이 필요 없다. 운영 DB는 이 작업에서 직접 변경하지 않았다.
2. 서버 IAM 역할과 버킷/리전을 설정하고 백엔드를 배포한다. 키를 코드나 FE에 넣지 않는다.
3. FE를 연결하여 발급→PUT→PATCH→GET→재로그인을 확인한다.

인프라 담당자가 전달한 버킷은 `stockspoon-profile-images-250832562715`, 리전은 `ap-northeast-2`, 객체 prefix는 `profile-images/`다. 전달받은 ECS task role `stockspoon-v2-prod-backend-task-role`의 권한은 해당 prefix의 `GetObject`·`PutObject`·`DeleteObject`와 버킷 `GetBucketLocation`이다. 앱은 GetObject·PutObject만 사용하며 ListBucket/HEAD에 의존하지 않는다. `PROFILE_IMAGE_S3_PUBLIC_BASE_URL`은 `https://stockspoon-profile-images-250832562715.s3.ap-northeast-2.amazonaws.com/profile-images/`로 설정한다. 이 S3 직접 URL로 브라우저가 읽으려면 해당 객체의 공개 읽기 정책이 필요하다. task role의 GetObject 권한만으로 익명 브라우저 읽기가 허용되지는 않는다. 실제 정책·CORS·배포 환경변수 적용 여부는 아직 검증하지 않았다.

S3 CORS 예시(Origin은 실제 FE 주소로 교체):

```json
[{"AllowedOrigins":["https://실제-FE-도메인"],"AllowedMethods":["PUT","GET","HEAD"],"AllowedHeaders":["content-type","x-amz-*"],"ExposeHeaders":["ETag"],"MaxAgeSeconds":300}]
```

임시 객체 lifecycle은 `profile-images/` 아래 숫자 회원 ID prefix(또는 `upload=temporary` 태그 등 실제 운영 정책)에만 적용한다. **`profile-images/` 전체 만료 규칙은 `profile-images/final/`도 삭제하므로 금지한다.** 현재 API가 태그 헤더를 요구하지 않으므로 태그 방식 도입 시 별도 인프라 또는 서명 계약 변경이 필요하다. 운영 정리는 S3 객체 수정 시각과 실제 users 참조를 대조하여 수행한다.

교체·이미지 삭제·회원 탈퇴·DB 실패로 남은 최종 파일은 현재 자동 삭제하지 않는다. 활성 users.profile_image_url 참조를 확인한 후 정리한다. 발급 이력 테이블은 없다.

롤백할 때는 이전 앱이 S3 키를 그대로 URL로 반환하고 카카오 재로그인 때 덮어쓸 수 있으므로, S3 이미지가 저장된 뒤에는 호환 처리를 포함한 롤백 버전을 준비한다.

## 검증 범위

로컬 테스트는 실제 Spring Security·JPA/H2·트랜잭션·AWS 서명 생성과, SDK를 대체한 S3 응답으로 검증한다. 실제 AWS 버킷·IAM·브라우저 CORS 및 업로드는 별도 연동 확인이 필요하다. 이번 변경에서는 운영 DB/AWS 설정을 적용하지 않는다.

2026-11-09 인프라 규격 변경 후 `ProfileImageServiceTests` 10건, `ProfileImageApiTests` 5건, `UserProfileTransactionTests` 3건, `KakaoAuthServiceTests` 6건, `KakaoAuthPersistenceTests` 3건으로 관련 테스트 총 27건이 통과했다. `git diff --check`도 통과했다. 전체 `test` 실행은 Gradle의 `build/test-results/test/binary/in-progress-results-generic.bin` 누락으로 실패했으므로 이번 변경의 전체 테스트 통과로 기록하지 않는다. 이전 버전의 전체 빌드·SpotBugs 통과 기록은 [구현 계획과 진행 기록](superpowers/plans/2026-11-02-profile-update.md)의 이전 검증 결과다.
