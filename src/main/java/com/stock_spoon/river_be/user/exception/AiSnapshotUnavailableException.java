package com.stock_spoon.river_be.user.exception;

public class AiSnapshotUnavailableException extends RuntimeException {
    public AiSnapshotUnavailableException() {
        super("대기 주문 종목의 현재가를 확인할 수 없습니다.");
    }
}
