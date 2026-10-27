package com.stock_spoon.river_be.user.controller;

import com.stock_spoon.river_be.config.DbReadRetryable;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.stock_spoon.river_be.user.dto.UserMeResponse;
import com.stock_spoon.river_be.user.service.UserService;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {
    private final UserService service;
    // [회원 탈퇴 추가] 삭제 절차는 withdrawal에, 성공 후 인증 쿠키 만료는 cookies에 맡긴다.
    private final com.stock_spoon.river_be.user.service.UserWithdrawalService withdrawal;
    private final com.stock_spoon.river_be.auth.cookie.AuthCookieService cookies;

    // [회원 탈퇴 수정] 기존 회원 조회 서비스와 함께 탈퇴 서비스·쿠키 처리기를 생성자 주입한다.
    public UserController(UserService service,
            com.stock_spoon.river_be.user.service.UserWithdrawalService withdrawal,
            com.stock_spoon.river_be.auth.cookie.AuthCookieService cookies) {
        this.service = service;
        this.withdrawal = withdrawal;
        this.cookies = cookies;
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
}
