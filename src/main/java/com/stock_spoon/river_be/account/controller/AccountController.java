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
import org.springframework.web.bind.annotation.RestController;
import com.stock_spoon.river_be.account.dto.AccountCreateRequest;
import com.stock_spoon.river_be.account.dto.AccountNameUpdateRequest;
import com.stock_spoon.river_be.account.dto.AccountResponse;
import com.stock_spoon.river_be.account.service.AccountService;

@RestController
@RequestMapping("/api/v1/users/me/accounts")
public class AccountController {
    private final AccountService service;

    public AccountController(AccountService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AccountResponse create(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody AccountCreateRequest request) {
        return service.create(Long.parseLong(jwt.getSubject()), request);
    }

    @PatchMapping("/{accountId}")
    public AccountResponse rename(@AuthenticationPrincipal Jwt jwt,
            @PathVariable long accountId,
            @RequestBody AccountNameUpdateRequest request) {
        return service.rename(Long.parseLong(jwt.getSubject()), accountId, request);
    }

    @GetMapping
    public List<AccountResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return service.list(Long.parseLong(jwt.getSubject()));
    }

    @GetMapping("/{accountId}")
    public AccountResponse get(@AuthenticationPrincipal Jwt jwt,
            @PathVariable long accountId) {
        return service.get(Long.parseLong(jwt.getSubject()), accountId);
    }
}
