package com.stock_spoon.river_be.user.service;

import com.stock_spoon.river_be.account.entity.Account;
import com.stock_spoon.river_be.account.repository.AccountRepository;
import com.stock_spoon.river_be.order.HoldingRepository;
import com.stock_spoon.river_be.order.Order;
import com.stock_spoon.river_be.order.OrderRepository;
import com.stock_spoon.river_be.user.dto.AiUserSnapshotResponse.AccountSnapshot;
import com.stock_spoon.river_be.user.dto.AiUserSnapshotResponse.PendingOrderSnapshot;
import com.stock_spoon.river_be.user.dto.AiUserSnapshotResponse.StockSnapshot;
import com.stock_spoon.river_be.user.dto.AiUserSnapshotResponse.UserSnapshot;
import com.stock_spoon.river_be.user.repository.UserRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 활성 AI 관리 계좌의 사용자·보유종목·대기 주문 정보를 반환한다. */
@Service
public class AiUserSnapshotService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AiUserSnapshotService.class);
    private final UserRepository users;
    private final AccountRepository accounts;
    private final HoldingRepository holdings;
    private final OrderRepository orders;

    public AiUserSnapshotService(UserRepository users, AccountRepository accounts,
            HoldingRepository holdings, OrderRepository orders) {
        this.users = users;
        this.accounts = accounts;
        this.holdings = holdings;
        this.orders = orders;
    }

    @Transactional(readOnly = true)
    public List<UserSnapshot> snapshot() {
        var managedAccounts = accounts.findActiveAiManaged();
        Map<Long, List<AccountSnapshot>> accountsByUser = new HashMap<>();
        if (!managedAccounts.isEmpty()) {
            var accountIds = managedAccounts.stream().map(Account::getId).toList();
            Map<Long, List<StockSnapshot>> stocksByAccount = new HashMap<>();
            for (var holding : holdings.findAllByAccountIds(accountIds)) {
                stocksByAccount.computeIfAbsent(holding.getAccountId(), ignored -> new ArrayList<>())
                        .add(new StockSnapshot(holding.getStockCode(), holding.getTotalCost(),
                                holding.getQuantity()));
            }
            Map<Long, List<PendingOrderSnapshot>> ordersByAccount = new HashMap<>();
            for (var order : orders.findAllByAccountIdsAndStatus(accountIds, Order.Status.PENDING)) {
                ordersByAccount.computeIfAbsent(order.getAccountId(), ignored -> new ArrayList<>())
                        .add(new PendingOrderSnapshot(order.getId(), order.getStockCode(),
                                order.getSide().name().toLowerCase(Locale.ROOT),
                                order.getStatus().name().toLowerCase(Locale.ROOT),
                                order.getType().name().toLowerCase(Locale.ROOT),
                                order.getLimitPrice(), order.getQuantity()));
            }
            for (var account : managedAccounts) {
                accountsByUser.computeIfAbsent(account.getUser().getId(), ignored -> new ArrayList<>())
                        .add(new AccountSnapshot(account.getId(), account.getName(), account.isActive(),
                                account.getCashBalance(),
                                List.copyOf(stocksByAccount.getOrDefault(account.getId(), List.of())),
                                List.copyOf(ordersByAccount.getOrDefault(account.getId(), List.of()))));
            }
        }
        var result = users.findAllByOrderByIdAsc().stream()
                .map(user -> new UserSnapshot(user.getId(),
                        List.copyOf(accountsByUser.getOrDefault(user.getId(), List.of()))))
                .toList();
        log.info("event=ai_snapshot_completed userCount={} accountCount={}", result.size(), managedAccounts.size());
        return result;
    }

}
