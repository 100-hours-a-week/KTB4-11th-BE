package com.stock_spoon.river_be.user.service;

import com.stock_spoon.river_be.account.entity.Account;
import com.stock_spoon.river_be.account.repository.AccountRepository;
import com.stock_spoon.river_be.order.HoldingRepository;
import com.stock_spoon.river_be.order.Order;
import com.stock_spoon.river_be.order.OrderRepository;
import com.stock_spoon.river_be.market.kiwoom.KiwoomStockStream;
import com.stock_spoon.river_be.user.dto.AiUserSnapshotResponse.AccountSnapshot;
import com.stock_spoon.river_be.user.dto.AiUserSnapshotResponse.PendingOrderSnapshot;
import com.stock_spoon.river_be.user.dto.AiUserSnapshotResponse.StockSnapshot;
import com.stock_spoon.river_be.user.dto.AiUserSnapshotResponse.UserSnapshot;
import com.stock_spoon.river_be.user.exception.AiSnapshotUnavailableException;
import com.stock_spoon.river_be.user.repository.UserRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 활성 AI 관리 계좌의 DB 데이터와 유효한 구독의 마지막 현재가를 함께 반환한다. */
@Service
public class AiUserSnapshotService {
    private final UserRepository users;
    private final AccountRepository accounts;
    private final HoldingRepository holdings;
    private final OrderRepository orders;
    private final KiwoomStockStream stream;

    public AiUserSnapshotService(UserRepository users, AccountRepository accounts,
            HoldingRepository holdings, OrderRepository orders, KiwoomStockStream stream) {
        this.users = users;
        this.accounts = accounts;
        this.holdings = holdings;
        this.orders = orders;
        this.stream = stream;
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
            Map<String, BigDecimal> pricesByStock = new HashMap<>();
            for (var order : orders.findAllByAccountIdsAndStatus(accountIds, Order.Status.PENDING)) {
                BigDecimal price = pricesByStock.computeIfAbsent(order.getStockCode(), code ->
                        stream.latest(code).orElseThrow(AiSnapshotUnavailableException::new).currentPrice());
                ordersByAccount.computeIfAbsent(order.getAccountId(), ignored -> new ArrayList<>())
                        .add(new PendingOrderSnapshot(order.getId(), order.getStockCode(),
                                order.getSide().name().toLowerCase(Locale.ROOT),
                                order.getStatus().name().toLowerCase(Locale.ROOT),
                                order.getType().name().toLowerCase(Locale.ROOT),
                                order.getLimitPrice(), order.getQuantity(), price));
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

}
