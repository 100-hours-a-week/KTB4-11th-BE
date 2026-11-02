package com.stock_spoon.river_be.user.controller;

import com.stock_spoon.river_be.config.DbReadRetryable;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import com.stock_spoon.river_be.user.service.UserService;
import com.stock_spoon.river_be.user.service.ProfileImageService;
import com.stock_spoon.river_be.user.dto.*;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;
import org.springframework.http.ResponseEntity;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {
    private final UserService service;
    private final ProfileImageService images;
    // [회원 탈퇴 추가] 삭제 절차는 withdrawal에, 성공 후 인증 쿠키 만료는 cookies에 맡긴다.
    private final com.stock_spoon.river_be.user.service.UserWithdrawalService withdrawal;
    private final com.stock_spoon.river_be.auth.cookie.AuthCookieService cookies;

    // [회원 탈퇴 수정] 기존 회원 조회 서비스와 함께 탈퇴 서비스·쿠키 처리기를 생성자 주입한다.
    public UserController(UserService service,
            com.stock_spoon.river_be.user.service.UserWithdrawalService withdrawal,
            com.stock_spoon.river_be.auth.cookie.AuthCookieService cookies, ProfileImageService images) {
        this.service = service;
        this.withdrawal = withdrawal;
        this.cookies = cookies;
        this.images = images;
    }

    // [회원 탈퇴 추가] 본문의 회원 ID 대신 인증된 JWT subject를 사용하며, 삭제 성공 후에만 쿠키를 만료하고 204를 반환한다.
    @org.springframework.web.bind.annotation.DeleteMapping("/me")
    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void deleteMe(@AuthenticationPrincipal Jwt jwt, jakarta.servlet.http.HttpServletResponse response) {
        withdrawal.withdraw(Long.parseLong(jwt.getSubject()));
        cookies.clear(response);
    }

    @DbReadRetryable
    @GetMapping("/me")
    public UserMeResponse getMe(@AuthenticationPrincipal Jwt jwt) {
        return service.getMe(Long.parseLong(jwt.getSubject()));
    }

    @PostMapping("/me/profile-image/presigned-url")
    public ProfileImageUploadResponse issueImageUrl(@AuthenticationPrincipal Jwt jwt, @RequestBody JsonNode body) {
        return images.issue(Long.parseLong(jwt.getSubject()), ProfileImageUploadRequest.parse(body));
    }

    @PatchMapping("/me")
    public Map<String, String> updateMe(@AuthenticationPrincipal Jwt jwt, @RequestBody JsonNode body) {
        service.updateMe(Long.parseLong(jwt.getSubject()), UserProfileUpdateRequest.parse(body));
        return Map.of("message", "success");
    }

    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    public ResponseEntity<Map<String, String>> profileDatabaseUnavailable(RuntimeException error) {
        return ResponseEntity.status(503).body(Map.of("code", "USER_STORAGE_UNAVAILABLE",
                "message", "회원 저장소를 사용할 수 없습니다. 잠시 후 다시 시도해주세요."));
    }
}
