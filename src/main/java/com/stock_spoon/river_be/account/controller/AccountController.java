package com.stock_spoon.river_be.account.controller;

import java.util.List;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.stock_spoon.river_be.account.dto.AccountCreateRequest;
import com.stock_spoon.river_be.account.dto.AccountCreateResponse;
import com.stock_spoon.river_be.account.dto.AccountDetailResponse;
import com.stock_spoon.river_be.account.dto.AccountListResponse;
import com.stock_spoon.river_be.account.dto.AccountNameUpdateRequest;
import com.stock_spoon.river_be.account.dto.AccountResponse;
import com.stock_spoon.river_be.account.service.AccountService;
import com.stock_spoon.river_be.account.service.AccountHoldingsService;
import com.stock_spoon.river_be.account.dto.AccountHoldingsResponse;
import com.stock_spoon.river_be.dbwrite.DbWriteReplayAcceptedResponse;
import com.stock_spoon.river_be.dbwrite.DbWriteReplayService;
import org.springframework.http.ResponseEntity;

@RestController
@RequestMapping("/api/v1/users/me/accounts")
public class AccountController {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AccountController.class);
    private final AccountService service;
    private final AccountHoldingsService holdings;
    private final DbWriteReplayService writeReplay;

    public AccountController(AccountService service, AccountHoldingsService holdings,
            DbWriteReplayService writeReplay) {
        this.service = service;
        this.holdings = holdings;
        this.writeReplay = writeReplay;
    }

    @PostMapping
    public ResponseEntity<?> create(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody AccountCreateRequest request) {
        long userId = Long.parseLong(jwt.getSubject());
        AccountCreateResponse result;
        try {
            result = service.create(userId, request);
        } catch (RuntimeException failure) {
            var queued = writeReplay.enqueueIfWriterUnavailable(failure, DbWriteReplayService.Operation.ACCOUNT_CREATE,
                    new DbWriteReplayService.AccountCreatePayload(userId, request));
            if (queued.isPresent()) {
                return ResponseEntity.accepted().body(DbWriteReplayAcceptedResponse.queued(queued.get()));
            }
            throw failure;
        }
        log.info("event=account_created");
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    @PatchMapping("/{accountId}")
    public ResponseEntity<?> rename(@AuthenticationPrincipal Jwt jwt,
            @PathVariable long accountId,
            @RequestBody AccountNameUpdateRequest request) {
        long userId = Long.parseLong(jwt.getSubject());
        AccountResponse result;
        try {
            result = service.rename(userId, accountId, request);
        } catch (RuntimeException failure) {
            var queued = writeReplay.enqueueIfWriterUnavailable(failure, DbWriteReplayService.Operation.ACCOUNT_RENAME,
                    new DbWriteReplayService.AccountRenamePayload(userId, accountId, request));
            if (queued.isPresent()) {
                return ResponseEntity.accepted().body(DbWriteReplayAcceptedResponse.queued(queued.get()));
            }
            throw failure;
        }
        log.info("event=account_renamed");
        return ResponseEntity.ok(result);
    }

    @GetMapping
    public List<AccountListResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return service.list(Long.parseLong(jwt.getSubject()));
    }

    @GetMapping("/{accountId}")
    public AccountDetailResponse get(@AuthenticationPrincipal Jwt jwt,
            @PathVariable long accountId) {
        return service.get(Long.parseLong(jwt.getSubject()), accountId);
    }

    // 홈은 limit=3, 전체 페이지는 limit 없이 호출한다. 정렬·계산·접근 검증은 서비스에서 처리한다.
    @GetMapping("/{accountId}/holdings")
    public AccountHoldingsResponse holdings(@AuthenticationPrincipal Jwt jwt,
            @PathVariable long accountId,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String order,
            @RequestParam(required = false) String limit) {
        return holdings.list(Long.parseLong(jwt.getSubject()), accountId, sort, order, limit);
    }
}
