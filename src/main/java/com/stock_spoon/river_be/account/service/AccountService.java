package com.stock_spoon.river_be.account.service;

import java.util.List;
import java.util.Map;
import java.util.HashMap;
import com.stock_spoon.river_be.holding.repository.HoldingRepository;
import com.stock_spoon.river_be.market.RedisMarketPrice;
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
import com.stock_spoon.river_be.order.service.OrderService;
import com.stock_spoon.river_be.order.entity.Order;
import com.stock_spoon.river_be.order.repository.OrderRepository;

@Service
public class AccountService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AccountService.class);
    private static final String ONBOARDING_ACCOUNT_NAME = "기본 계좌";
    private static final String DEFAULT_ACCOUNT_NAME_PREFIX = "기본 계좌 ";

    private final AccountRepository accountRepository;
    private final UserRepository userRepository;
    private final OrderService orderService;
    private final OrderRepository orders;
    private final HoldingRepository holdings;
    private final RedisMarketPrice market;
    private final TransactionTemplate read;
    private final com.stock_spoon.river_be.competition.repository.CompetitionParticipantRepository participants;

    public AccountService(AccountRepository accountRepository, UserRepository userRepository,
            OrderService orderService, OrderRepository orders, HoldingRepository holdings,
            RedisMarketPrice market,
            PlatformTransactionManager transactions,
            com.stock_spoon.river_be.competition.repository.CompetitionParticipantRepository participants) {
        this.participants = participants;
        this.accountRepository = accountRepository;
        this.userRepository = userRepository;
        this.orderService = orderService;
        this.orders = orders;
        this.holdings = holdings;
        this.market = market;
        this.read = new TransactionTemplate(transactions);
        this.read.setReadOnly(true);
    }

    @Transactional
    public AccountResponse onboard(long userId, OnboardingRequest request) {
        var user = userRepository.findLockedById(userId)
                .orElseThrow(() -> new AccountException(HttpStatus.NOT_FOUND,
                        "USER_NOT_FOUND", "사용자를 찾을 수 없습니다."));

        if (user.isOnboardingCompleted() || accountRepository.existsByUserId(userId)) {
            throw new AccountException(HttpStatus.CONFLICT,
                    "ONBOARDING_ALREADY_COMPLETED", "이미 온보딩을 완료했습니다.");
        }

        var account = accountRepository.save(
                new Account(user, ONBOARDING_ACCOUNT_NAME, request.initialCapital(), false));
        user.completeOnboarding();
        return AccountResponse.from(account);
    }

    @Transactional
    public AccountCreateResponse create(long userId, AccountCreateRequest request) {
        var user = userRepository.findLockedById(userId)
                .orElseThrow(() -> new AccountException(HttpStatus.NOT_FOUND,
                        "USER_NOT_FOUND", "사용자를 찾을 수 없습니다."));
        if (!user.isOnboardingCompleted()) {
            throw new AccountException(HttpStatus.CONFLICT,
                    "ONBOARDING_REQUIRED", "먼저 온보딩을 완료해주세요.");
        }

        String name = request.accountName() == null || request.accountName().isBlank()
                ? nextDefaultName(userId)
                : normalizeName(request.accountName());

        if (accountRepository.existsByUserIdAndNameIgnoreCase(userId, name)) {
            throw new AccountException(HttpStatus.CONFLICT,
                    "DUPLICATE_ACCOUNT_NAME", "이미 사용 중인 계좌 이름입니다.");
        }

        return AccountCreateResponse.from(accountRepository.save(
                new Account(user, name, request.initialCapital(), false)));
    }

    @Transactional
    public AccountResponse rename(long userId, long accountId, AccountNameUpdateRequest request) {
        // 대결 생성과 일반 계좌 이름 변경도 같은 사용자 잠금을 사용한다.
        userRepository.findLockedById(userId).orElseThrow(() -> new AccountException(
                HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "사용자를 찾을 수 없습니다."));
        var account = accountRepository.findByIdAndUserIdAndActiveTrue(accountId, userId)
                .orElseThrow(() -> new AccountException(HttpStatus.NOT_FOUND,
                        "ACCOUNT_NOT_FOUND", "계좌를 찾을 수 없습니다."));
        String name = normalizeName(request.accountName());

        if (account.getName().equals(name)) {
            return AccountResponse.from(account);
        }
        if (accountRepository.existsByUserIdAndNameIgnoreCaseAndIdNot(
                userId, name, accountId)) {
            throw new AccountException(HttpStatus.CONFLICT,
                    "DUPLICATE_ACCOUNT_NAME", "이미 사용 중인 계좌 이름입니다.");
        }

        account.rename(name);
        return AccountResponse.from(account);
    }

    public List<AccountListResponse> list(long userId) {
        var availableCash = new HashMap<Long, Long>();
        var snapshot = read.execute(status -> {
            var rows = accountRepository.findAllByUserIdAndActiveTrueOrderByCreatedAtAscIdAsc(userId);
            for (var account : rows) {
                availableCash.put(account.getId(), orderService.availableCash(account.getId()));
            }
            return snapshot(rows, false);
        });
        var values = evaluate(snapshot);
        log.info("event=account_valuation_completed accountCount={} holdingCount={}", snapshot.accounts().size(), snapshot.holdings().size());
        return snapshot.accounts().stream().map(a -> AccountListResponse.from(a,
                values.getOrDefault(a.getId(), 0L), snapshot.competitionAccountIds().contains(a.getId()),
                availableCash.get(a.getId()))).toList();
    }

    // 호출 서비스가 소유권을 확인한 계좌만 전달한다. 시세 요청은 DB 트랜잭션 밖에서 수행한다.
    public Map<Long, Double> returnPercents(List<Account> accounts) {
        if (accounts.isEmpty()) return Map.of();
        var snapshot = read.execute(status -> snapshot(accounts, false));
        var values = evaluate(snapshot);
        var result = new HashMap<Long, Double>();
        for (var account : snapshot.accounts()) {
            result.put(account.getId(), AccountListResponse.from(account,
                    values.getOrDefault(account.getId(), 0L)).returnPercent());
        }
        return result;
    }

    public AccountDetailResponse get(long userId, long accountId) {
        var snapshot = read.execute(status -> {
            var account = accountRepository.findByIdAndUserIdAndActiveTrue(accountId, userId)
                    .orElseThrow(() -> new AccountException(HttpStatus.NOT_FOUND,
                            "ACCOUNT_NOT_FOUND", "계좌를 찾을 수 없습니다."));
            return snapshot(List.of(account), true);
        });
        var values = evaluate(snapshot);
        log.info("event=account_valuation_completed accountCount={} holdingCount={}", snapshot.accounts().size(), snapshot.holdings().size());
        return AccountDetailResponse.from(snapshot.accounts().getFirst(), snapshot.availableCash(),
                values.getOrDefault(accountId, 0L), snapshot.executedTradeCount(), snapshot.competitionAccountIds().contains(accountId));
    }

    private Snapshot snapshot(List<Account> accounts, boolean includeAvailableCash) {
        var owned = accounts.isEmpty() ? List.<Owned>of() : holdings.findAllByAccountIds(
                accounts.stream().map(Account::getId).toList()).stream()
                .map(h -> new Owned(h.getAccountId(), h.getStockCode(), h.getQuantity())).toList();
        long available = includeAvailableCash ? orderService.availableCash(accounts.getFirst().getId()) : 0;
        long executedCount = includeAvailableCash
                ? orders.countByAccountIdAndStatus(accounts.getFirst().getId(), Order.Status.EXECUTED) : 0;
        // 한 번에 대결 계좌 ID를 조회해 목록의 각 계좌마다 DB를 다시 조회하지 않는다.
        var competitionIds = accounts.isEmpty() ? java.util.Set.<Long>of()
                : participants.findCompetitionAccountIds(accounts.stream().map(Account::getId).toList());
        return new Snapshot(List.copyOf(accounts), owned, available, executedCount, competitionIds);
    }

    private Map<Long, Long> evaluate(Snapshot snapshot) {
        var prices = new HashMap<String, Long>();
        var values = new HashMap<Long, Long>();
        for (var holding : snapshot.holdings()) {
            long price;
            try {
                price = prices.computeIfAbsent(holding.code(), market::currentPrice);
            } catch (IllegalStateException error) {
                log.warn("event=account_valuation_failed stockCode={} causeType={}", holding.code(), error.getClass().getSimpleName());
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
    private record Snapshot(List<Account> accounts, List<Owned> holdings, long availableCash, long executedTradeCount,
            java.util.Set<Long> competitionAccountIds) {}
    private String nextDefaultName(long userId) {
        int number = 1;
        while (accountRepository.existsByUserIdAndNameIgnoreCase(
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
