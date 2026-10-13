package com.stock_spoon.river_be.execution.service;

import com.stock_spoon.river_be.account.repository.AccountRepository;
import com.stock_spoon.river_be.execution.entity.ExecutionRequest;
import com.stock_spoon.river_be.execution.repository.ExecutionRequestRepository;
import com.stock_spoon.river_be.order.entity.Order;
import com.stock_spoon.river_be.order.exception.OrderException;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExecutionRequestService {
    private final AccountRepository accounts;
    private final ExecutionRequestRepository requests;
    private final jakarta.persistence.EntityManager entities;
    public ExecutionRequestService(AccountRepository accounts, ExecutionRequestRepository requests,
            jakarta.persistence.EntityManager entities) {
        this.accounts = accounts; this.requests = requests; this.entities = entities;
    }
    @Transactional
    public long execute(String requestId, long userId, long accountId, String hash, Supplier<Order> action) {
        // 동일 계좌의 중복 호출도 기존 체결과 같은 잠금 순서로 직렬화한다.
        var account = accounts.findLockedById(accountId).orElseThrow(() -> new OrderException("계좌를 찾을 수 없습니다."));
        if (account.getUser().getId() != userId || !account.isActive() || !account.isAiManaged())
            throw new OrderException(HttpStatus.FORBIDDEN, "FORBIDDEN_ACCOUNT", "운용 가능한 계좌가 아닙니다.");
        var previous = requests.findById(requestId);
        if (previous.isPresent()) {
            if (!previous.get().matches(userId, accountId, hash))
                throw new OrderException(HttpStatus.CONFLICT, "EXECUTION_REQUEST_CONFLICT", "같은 요청 ID에 다른 입력을 사용할 수 없습니다.");
            return previous.get().orderId();
        }
        var order = action.get();
        // 직접 지정한 ID도 반드시 INSERT한다. merge는 다른 계좌의 기존 기록을 덮어볼 수 있다.
        entities.persist(new ExecutionRequest(requestId, userId, accountId, hash, order));
        entities.flush();
        return order.getId();
    }
    @Transactional(readOnly = true)
    public java.util.Optional<Long> result(String id, long userId, long accountId, String hash) {
        if (accounts.findByIdAndUserIdAndActiveTrue(accountId, userId).isEmpty())
            throw new OrderException(HttpStatus.FORBIDDEN, "FORBIDDEN_ACCOUNT", "계좌를 조회할 권한이 없습니다.");
        return requests.findById(id).map(value -> {
            if (!value.matches(userId, accountId, hash))
                throw new OrderException(HttpStatus.CONFLICT, "EXECUTION_REQUEST_CONFLICT", "요청 입력을 확인하세요.");
            return value.orderId();
        });
    }
}
