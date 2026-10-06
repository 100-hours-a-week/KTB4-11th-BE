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
import org.springframework.web.bind.annotation.ResponseStatus;
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

@RestController
@RequestMapping("/api/v1/users/me/accounts")
public class AccountController {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AccountController.class);
    private final AccountService service;
    private final AccountHoldingsService holdings;

    public AccountController(AccountService service, AccountHoldingsService holdings) {
        this.service = service;
        this.holdings = holdings;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AccountCreateResponse create(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody AccountCreateRequest request) {
        var result = service.create(Long.parseLong(jwt.getSubject()), request);
        log.info("event=account_created");
        return result;
    }

    @PatchMapping("/{accountId}")
    public AccountResponse rename(@AuthenticationPrincipal Jwt jwt,
            @PathVariable long accountId,
            @RequestBody AccountNameUpdateRequest request) {
        var result = service.rename(Long.parseLong(jwt.getSubject()), accountId, request);
        log.info("event=account_renamed");
        return result;
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
