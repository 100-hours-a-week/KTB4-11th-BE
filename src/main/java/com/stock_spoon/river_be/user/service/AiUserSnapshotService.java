package com.stock_spoon.river_be.user.service;

import com.stock_spoon.river_be.account.entity.Account;
import com.stock_spoon.river_be.account.repository.AccountRepository;
import com.stock_spoon.river_be.order.HoldingRepository;
import com.stock_spoon.river_be.order.Order;
import com.stock_spoon.river_be.order.OrderRepository;
import com.stock_spoon.river_be.user.repository.UserRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** AI 조회 응답에 사용할 DB 데이터. 현재가는 이후 단계에서 조회한다. */
@Service
public class AiUserSnapshotService {
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
                                order.getSide(), order.getStatus(), order.getType(),
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
        return users.findAllByOrderByIdAsc().stream()
                .map(user -> new UserSnapshot(user.getId(),
                        List.copyOf(accountsByUser.getOrDefault(user.getId(), List.of()))))
                .toList();
    }

    public record UserSnapshot(long userId, List<AccountSnapshot> accounts) {}

    public record AccountSnapshot(long accountId, String accountName, boolean active,
            long cashBalance, List<StockSnapshot> stocks, List<PendingOrderSnapshot> pendingOrders) {}

    public record StockSnapshot(String stockCode, BigDecimal totalCost, long quantity) {}

    public record PendingOrderSnapshot(long orderId, String stockCode, Order.Side side,
            Order.Status status, Order.Type type, Long limitPrice, long quantity) {}
}
