package com.stock_spoon.river_be.account.service;

import java.util.List;
import java.util.Map;
import java.util.HashMap;
import com.stock_spoon.river_be.order.HoldingRepository;
import com.stock_spoon.river_be.market.kiwoom.KiwoomMarketClient;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.stock_spoon.river_be.account.dto.AccountCreateRequest;
import com.stock_spoon.river_be.account.dto.AccountCreateResponse;
import com.stock_spoon.river_be.account.dto.AccountDetailResponse;
import com.stock_spoon.river_be.account.dto.AccountListResponse;
import com.stock_spoon.river_be.account.dto.AccountNameUpdateRequest;
import com.stock_spoon.river_be.account.dto.AccountResponse;
import com.stock_spoon.river_be.account.dto.OnboardingRequest;
import com.stock_spoon.river_be.account.entity.Account;
import com.stock_spoon.river_be.account.exception.AccountException;
import com.stock_spoon.river_be.account.repository.AccountRepository;
import com.stock_spoon.river_be.user.repository.UserRepository;
import com.stock_spoon.river_be.order.OrderService;

@Service
public class AccountService {
    private static final String ONBOARDING_ACCOUNT_NAME = "기본 계좌";
    private static final String DEFAULT_ACCOUNT_NAME_PREFIX = "기본 계좌 ";

    private final AccountRepository accountRepository;
    private final UserRepository userRepository;
    private final OrderService orderService;
    private final HoldingRepository holdings;
    private final KiwoomMarketClient market;
    private final TransactionTemplate read;

    public AccountService(AccountRepository accountRepository, UserRepository userRepository,
            OrderService orderService, HoldingRepository holdings,
            KiwoomMarketClient market,
            PlatformTransactionManager transactions) {
        this.accountRepository = accountRepository;
        this.userRepository = userRepository;
        this.orderService = orderService;
        this.holdings = holdings;
        this.market = market;
        this.read = new TransactionTemplate(transactions);
        this.read.setReadOnly(true);
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
    public AccountCreateResponse create(long userId, AccountCreateRequest request) {
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

        return AccountCreateResponse.from(accountRepository.save(
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

    public List<AccountListResponse> list(long userId) {
        var snapshot = read.execute(status -> {
            var rows = accountRepository.findAllByUserIdAndActiveTrueOrderByCreatedAtAscIdAsc(userId);
            return snapshot(rows, false);
        });
        var values = evaluate(snapshot);
        return snapshot.accounts().stream().map(a -> AccountListResponse.from(a,
                values.getOrDefault(a.getId(), 0L))).toList();
    }

    public AccountDetailResponse get(long userId, long accountId) {
        var snapshot = read.execute(status -> {
            var account = accountRepository.findByIdAndUserIdAndActiveTrue(accountId, userId)
                    .orElseThrow(() -> new AccountException(HttpStatus.NOT_FOUND,
                            "ACCOUNT_NOT_FOUND", "계좌를 찾을 수 없습니다."));
            return snapshot(List.of(account), true);
        });
        var values = evaluate(snapshot);
        return AccountDetailResponse.from(snapshot.accounts().getFirst(), snapshot.availableCash(),
                values.getOrDefault(accountId, 0L));
    }

    private Snapshot snapshot(List<Account> accounts, boolean includeAvailableCash) {
        var owned = accounts.isEmpty() ? List.<Owned>of() : holdings.findAllByAccountIds(
                accounts.stream().map(Account::getId).toList()).stream()
                .map(h -> new Owned(h.getAccountId(), h.getStockCode(), h.getQuantity())).toList();
        long available = includeAvailableCash ? orderService.availableCash(accounts.getFirst().getId()) : 0;
        return new Snapshot(List.copyOf(accounts), owned, available);
    }

    private Map<Long, Long> evaluate(Snapshot snapshot) {
        var prices = new HashMap<String, Long>();
        var values = new HashMap<Long, Long>();
        for (var holding : snapshot.holdings()) {
            long price;
            try {
                price = prices.computeIfAbsent(holding.code(), market::currentPrice);
            } catch (IllegalStateException error) {
                throw new AccountException(HttpStatus.SERVICE_UNAVAILABLE, "HOLDINGS_DATA_UNAVAILABLE",
                        "보유 종목 정보를 조회할 수 없습니다.");
            }
            if (price <= 0 || holding.quantity() <= 0) {
                throw new AccountException(HttpStatus.INTERNAL_SERVER_ERROR, "INVALID_HOLDINGS_DATA",
                        "보유 데이터를 확인해주세요.");
            }
            values.merge(holding.accountId(), Math.multiplyExact(price, holding.quantity()), Math::addExact);
        }
        return values;
    }

    private record Owned(long accountId, String code, long quantity) {}
    private record Snapshot(List<Account> accounts, List<Owned> holdings, long availableCash) {}
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
