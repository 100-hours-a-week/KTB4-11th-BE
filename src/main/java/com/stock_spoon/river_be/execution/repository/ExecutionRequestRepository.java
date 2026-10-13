package com.stock_spoon.river_be.execution.repository;

import com.stock_spoon.river_be.execution.entity.ExecutionRequest;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExecutionRequestRepository extends JpaRepository<ExecutionRequest, String> {}
