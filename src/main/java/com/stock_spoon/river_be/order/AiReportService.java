package com.stock_spoon.river_be.order;

import com.stock_spoon.river_be.account.repository.AccountRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AiReportService {
    private final AccountRepository accounts;
    private final OrderRepository orders;
    private final ExecutionRepository executions;

    public AiReportService(AccountRepository accounts, OrderRepository orders, ExecutionRepository executions) {
        this.accounts = accounts;
        this.orders = orders;
        this.executions = executions;
    }

    @Transactional(readOnly = true)
    public AiReportResponse get(long userId, long accountId, long orderId) {
        accounts.findByIdAndUserIdAndActiveTrue(accountId, userId).orElseThrow(() ->
                new OrderException(HttpStatus.FORBIDDEN, "FORBIDDEN_ACCOUNT", "이 계좌를 조회할 권한이 없습니다."));
        var order = orders.findByIdAndAccountId(orderId, accountId).orElseThrow(AiReportService::notFound);
        if (order.getSource() != Order.Source.AI || order.getReport() == null) throw notFound();
        return AiReportResponse.from(order, executions.findForOrder(orderId));
    }

    private static OrderException notFound() {
        return new OrderException(HttpStatus.NOT_FOUND, "AI_REPORT_NOT_FOUND", "AI 매매 리포트를 찾을 수 없습니다.");
    }
}
