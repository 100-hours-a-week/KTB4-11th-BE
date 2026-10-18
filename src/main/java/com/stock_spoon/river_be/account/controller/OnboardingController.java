package com.stock_spoon.river_be.account.controller;

import com.stock_spoon.river_be.account.dto.AccountResponse;
import com.stock_spoon.river_be.account.dto.OnboardingRequest;
import com.stock_spoon.river_be.account.service.AccountService;
import com.stock_spoon.river_be.dbwrite.DbWriteReplayAcceptedResponse;
import com.stock_spoon.river_be.dbwrite.DbWriteReplayService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users/me")
public class OnboardingController {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(OnboardingController.class);
    private final AccountService service;
    private final DbWriteReplayService writeReplay;

    public OnboardingController(AccountService service, DbWriteReplayService writeReplay) {
        this.service = service;
        this.writeReplay = writeReplay;
    }

    @PostMapping
    public ResponseEntity<?> onboard(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody OnboardingRequest request) {
        long userId = Long.parseLong(jwt.getSubject());
        AccountResponse result;
        try {
            result = service.onboard(userId, request);
        } catch (RuntimeException failure) {
            var queued = writeReplay.enqueueIfWriterUnavailable(failure, DbWriteReplayService.Operation.ACCOUNT_ONBOARD,
                    new DbWriteReplayService.AccountOnboardPayload(userId, request));
            if (queued.isPresent()) {
                return ResponseEntity.accepted().body(DbWriteReplayAcceptedResponse.queued(queued.get()));
            }
            throw failure;
        }
        log.info("event=onboarding_completed");
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }
}
