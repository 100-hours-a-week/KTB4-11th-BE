package com.stock_spoon.river_be.account.controller;

import com.stock_spoon.river_be.account.dto.AccountResponse;
import com.stock_spoon.river_be.account.dto.OnboardingRequest;
import com.stock_spoon.river_be.account.service.AccountService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users/me")
public class OnboardingController {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(OnboardingController.class);
    private final AccountService service;

    public OnboardingController(AccountService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AccountResponse onboard(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody OnboardingRequest request) {
        var result = service.onboard(Long.parseLong(jwt.getSubject()), request);
        log.info("event=onboarding_completed");
        return result;
    }
}
