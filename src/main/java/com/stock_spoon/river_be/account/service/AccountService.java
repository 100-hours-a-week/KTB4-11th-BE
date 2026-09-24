package com.stock_spoon.river_be.account.service;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.stock_spoon.river_be.account.dto.AccountCreateRequest;
import com.stock_spoon.river_be.account.dto.AccountNameUpdateRequest;
import com.stock_spoon.river_be.account.dto.AccountResponse;
import com.stock_spoon.river_be.account.dto.OnboardingRequest;
import com.stock_spoon.river_be.account.entity.Account;
import com.stock_spoon.river_be.account.exception.AccountException;
import com.stock_spoon.river_be.account.repository.AccountRepository;
import com.stock_spoon.river_be.user.repository.UserRepository;

@Service
public class AccountService {
    private static final String ONBOARDING_ACCOUNT_NAME = "기본 계좌";
    private static final String DEFAULT_ACCOUNT_NAME_PREFIX = "기본 계좌 ";

    private final AccountRepository accountRepository;
    private final UserRepository userRepository;

    public AccountService(AccountRepository accountRepository, UserRepository userRepository) {
        this.accountRepository = accountRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public AccountResponse onboard(long userId, OnboardingRequest request) {
        var user = userRepository.findById(userId)
                .orElseThrow(() -> new AccountException(HttpStatus.NOT_FOUND,
                        "USER_NOT_FOUND", "사용자를 찾을 수 없습니다."));

        if (user.isOnboardingCompleted() || accountRepository.existsByUserId(userId)) {
            throw new AccountException(HttpStatus.CONFLICT,
                    "ONBOARDING_ALREADY_COMPLETED", "이미 온보딩을 완료했습니다.");
        }

        var account = accountRepository.save(
                new Account(user, ONBOARDING_ACCOUNT_NAME, request.initialCapital()));
        user.completeOnboarding();
        return AccountResponse.from(account);
    }

    @Transactional
    public AccountResponse create(long userId, AccountCreateRequest request) {
        var user = userRepository.findById(userId)
                .orElseThrow(() -> new AccountException(HttpStatus.NOT_FOUND,
                        "USER_NOT_FOUND", "사용자를 찾을 수 없습니다."));
        if (!user.isOnboardingCompleted()) {
            throw new AccountException(HttpStatus.CONFLICT,
                    "ONBOARDING_REQUIRED", "먼저 온보딩을 완료해주세요.");
        }

        String name = request.accountName() == null || request.accountName().isBlank()
                ? nextDefaultName(userId)
                : normalizeName(request.accountName());

        if (accountRepository.existsByUserIdAndNameIgnoreCaseAndActiveTrue(userId, name)) {
            throw new AccountException(HttpStatus.CONFLICT,
                    "DUPLICATE_ACCOUNT_NAME", "이미 사용 중인 계좌 이름입니다.");
        }

        return AccountResponse.from(accountRepository.save(
                new Account(user, name, request.initialCapital())));
    }

    @Transactional
    public AccountResponse rename(long userId, long accountId, AccountNameUpdateRequest request) {
        var account = accountRepository.findByIdAndUserIdAndActiveTrue(accountId, userId)
                .orElseThrow(() -> new AccountException(HttpStatus.NOT_FOUND,
                        "ACCOUNT_NOT_FOUND", "계좌를 찾을 수 없습니다."));
        String name = normalizeName(request.accountName());

        if (account.getName().equals(name)) {
            return AccountResponse.from(account);
        }
        if (accountRepository.existsByUserIdAndNameIgnoreCaseAndActiveTrueAndIdNot(
                userId, name, accountId)) {
            throw new AccountException(HttpStatus.CONFLICT,
                    "DUPLICATE_ACCOUNT_NAME", "이미 사용 중인 계좌 이름입니다.");
        }

        account.rename(name);
        return AccountResponse.from(account);
    }

    @Transactional(readOnly = true)
    public List<AccountResponse> list(long userId) {
        return accountRepository.findAllByUserIdAndActiveTrueOrderByCreatedAtAscIdAsc(userId)
                .stream()
                .map(AccountResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public AccountResponse get(long userId, long accountId) {
        return accountRepository.findByIdAndUserIdAndActiveTrue(accountId, userId)
                .map(AccountResponse::from)
                .orElseThrow(() -> new AccountException(HttpStatus.NOT_FOUND,
                        "ACCOUNT_NOT_FOUND", "계좌를 찾을 수 없습니다."));
    }

    private String nextDefaultName(long userId) {
        int number = 1;
        while (accountRepository.existsByUserIdAndNameIgnoreCaseAndActiveTrue(
                userId, DEFAULT_ACCOUNT_NAME_PREFIX + number)) {
            number++;
        }
        return DEFAULT_ACCOUNT_NAME_PREFIX + number;
    }

    private String normalizeName(String name) {
        if (name == null || name.isBlank()) {
            throw new AccountException(HttpStatus.BAD_REQUEST,
                    "INVALID_ACCOUNT_NAME", "계좌 이름을 입력해주세요.");
        }
        String normalized = name.trim();
        if (normalized.length() > 20) {
            throw new AccountException(HttpStatus.BAD_REQUEST,
                    "INVALID_ACCOUNT_NAME", "계좌 이름은 20자 이하여야 합니다.");
        }
        return normalized;
    }
}
